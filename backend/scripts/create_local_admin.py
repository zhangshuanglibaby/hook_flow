"""只向归属校验通过的本机独立开发库新增管理员，不重置已有账户。"""
import argparse
import getpass
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('local_dev', HERE / 'local_dev.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def encode_fields(fields):
    value = bytearray()
    for field in fields:
        data = field.encode('utf-8')
        local.require(1 <= len(data) <= 8192, '输入长度超限')
        value.extend(struct.pack('>I', len(data)))
        value.extend(data)
    return bytes(value)


def classpath():
    reports = local.BACKEND / 'target/surefire-reports'
    candidates = sorted(reports.glob('TEST-*.xml'), key=lambda p: p.stat().st_mtime, reverse=True)
    for report in candidates:
        tree = ET.parse(report)
        properties = {p.get('name'): p.get('value', '') for p in tree.findall('./properties/property')}
        for name in ('surefire.test.class.path', 'java.class.path'):
            value = properties.get(name, '')
            paths = [Path(p) for p in value.split(os.pathsep) if p]
            jars = [p.name for p in paths]
            if (any(p.startswith('spring-security-crypto-') for p in jars)
                    and any(p.startswith('mysql-connector-j-') for p in jars)
                    and all(p.exists() for p in paths)):
                return value
    raise local.LocalError('缺少现有后端依赖路径；先用项目Wrapper执行已有数据库迁移测试以生成Surefire报告')


def verify_login(config, username, password):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), local.NoRedirect())
    request = urllib.request.Request('http://127.0.0.1:' + str(config['port']) + '/api/v1/auth/login',
                                    data=json.dumps({'username': username, 'password': password}).encode(),
                                    headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with opener.open(request, timeout=8) as response:
            value = json.loads(response.read(8192))
            request_id = response.headers.get('X-Request-Id', '')
            local.require(response.status == 200 and bool(value.get('token'))
                          and str(uuid.UUID(request_id)) == request_id, '登录响应未通过验证')
        print('真实后端登录验证成功；Token不输出、不保存。')
    except urllib.error.HTTPError as failure:
        status = failure.code
        failure.close()
        raise local.LocalError('账号已创建，但登录验证返回HTTP ' + str(status) + '；未自动重试或重置') from None
    except (urllib.error.URLError, TimeoutError):
        print('账号已创建；后端尚不可达，启动后即可使用该账号登录。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--username', default='admin')
    parser.add_argument('--check', action='store_true', help='只编译Java助手并验证无输入拒绝，不连接数据库')
    args = parser.parse_args()
    if args.check:
        try:
            result = subprocess.run(['java', '--class-path', classpath(), str(HERE/'CreateLocalAdmin.java')],
                                    input=b'', capture_output=True, timeout=60)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            raise local.LocalError('Java21未就绪或编译检查超时') from None
        local.require(result.returncode == 1 and result.stdout.strip() == b'CREATE_FAILED'
                      and not result.stderr, 'Java编译或无输入拒绝检查未通过；原始输出不显示')
        print('Java助手编译及无输入安全拒绝通过；未连接数据库。')
        return 0
    local.require(sys.stdin.isatty(), '请在交互终端运行，密码只允许隐藏输入')
    local.require(args.username.strip() and len(args.username) <= 64, '账号须为1～64个非全空白字符')
    with local.lock('admin-create.lock'):
        config = local.load()
        local.docker_ready()
        container = local.inspect('container', config['container'])
        volume = local.inspect('volume', config['volume'])
        local.require(container is not None and volume is not None, '本机开发容器/卷不存在；不创建或连接其他库')
        local.owned(container, config, 'container')
        local.owned(volume, config, 'volume')
        local.require(container['State']['Running'], '开发数据库未运行，请先正常启动开发环境')
        local.require(local.sql(config, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='app_user'") == '1',
                      '后端尚未完成Flyway迁移；先启动一次正式后端')
        bindings = container.get('NetworkSettings', {}).get('Ports', {}).get('3306/tcp') or []
        local.require(len(bindings) == 1 and bindings[0]['HostIp'] == '127.0.0.1', '开发数据库不是唯一IPv4回环绑定')
        env = local.application_environment(config, int(bindings[0]['HostPort']))
        cp = classpath()
        password = getpass.getpass('新演示账号密码（隐藏输入）: ')
        confirm = getpass.getpass('再次输入密码（隐藏输入）: ')
        local.require(password == confirm, '两次密码不同；未创建账号')
        local.require(password.strip() and len(password.encode('utf-8')) <= 72, '密码须非全空白且不超过72个UTF-8字节')
        payload = encode_fields([env['DB_URL'], config['db_user'], config['db_password'], args.username, password])
        try:
            result = subprocess.run(['java', '--class-path', cp, str(HERE/'CreateLocalAdmin.java')],
                                    input=payload, capture_output=True, timeout=60)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            raise local.LocalError('Java未就绪或执行超时；请确认Java21，不自动重跑') from None
        outcome = result.stdout.decode('utf-8', errors='replace').strip()
        if result.returncode == 0 and outcome == 'ALREADY_EXISTS':
            print('同名账号已存在，未修改其密码、角色或会话。')
            return 0
        local.require(result.returncode == 0 and outcome == 'CREATED', '创建未确认成功；敏感诊断不显示，不自动重试或重置账号')
        print('已新增本机演示管理员：' + args.username + '；密码仅以BCrypt摘要入库。')
        verify_login(config, args.username, password)
        print('不会自动添加演示事件；可登录后通过页面创建接收端和测试事件。')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except local.LocalError as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError):
        print('操作未确认完成；敏感错误原文不显示，未执行账户重置。', file=sys.stderr)
        raise SystemExit(1)
