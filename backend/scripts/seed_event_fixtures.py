"""只向归属已确认的本机开发库新增30条多状态测试夹具，不更新旧记录。"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('admin_support', HERE / 'create_local_admin.py')
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
local = support.local


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='只编译Java助手，无数据库连接')
    args = parser.parse_args()
    command = ['java', '--class-path', support.classpath(), str(HERE/'SeedEventFixtures.java')]
    if args.check:
        result = subprocess.run(command, input=b'', capture_output=True, timeout=60)
        local.require(result.returncode == 1 and result.stdout.strip() == b'FIXTURE_FAILED' and not result.stderr,
                      '编译检查未通过；原始输出不显示')
        print('Java夹具编译及无输入拒绝通过；未连接数据库。')
        return 0
    with local.lock('event-fixtures.lock'):
        config = local.load()
        local.docker_ready()
        container = local.inspect('container', config['container'])
        volume = local.inspect('volume', config['volume'])
        local.require(container is not None and volume is not None, '独立开发容器或卷不存在')
        local.owned(container, config, 'container')
        local.owned(volume, config, 'volume')
        local.require(container['State']['Running'], '独立开发库未运行')
        ports = container.get('NetworkSettings', {}).get('Ports', {}).get('3306/tcp') or []
        local.require(len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1', '数据库必须为本机IPv4绑定')
        env = local.application_environment(config, int(ports[0]['HostPort']))
        payload = support.encode_fields([env['DB_URL'], config['db_user'], config['db_password'], config['master_key']])
        result = subprocess.run(command, input=payload, capture_output=True, timeout=60)
        outcome = result.stdout.decode(errors='replace').strip()
        local.require(result.returncode == 0 and outcome in ('CREATED_30', 'ALREADY_EXISTS'),
                      '夹具写入未确认；事务失败回滚，不自动重试，诊断不显示')
        if outcome == 'ALREADY_EXISTS':
            print('本批夹具已存在，未重复插入或覆盖；继续核对分布。')
        counts = local.sql(config, "SELECT current_status,COUNT(*) FROM event WHERE event_type='demo.fixture.v1' GROUP BY current_status ORDER BY current_status")
        expected = {'PENDING': 6, 'SUCCESS': 6, 'FINAL_FAILED': 6, 'WAIT_RETRY': 6, 'PAUSED': 6}
        actual = dict((line.split('\t')[0], int(line.split('\t')[1])) for line in counts.splitlines())
        local.require(actual == expected, '夹具状态分布不符；未修改已有记录')
        print('数据库夹具已就绪：30条，五状态各6条。具名端点已新增；旧数据保留。')
        print('前端清除筛选后刷新；只看本批时事件类型输入 demo.fixture.v1。')
        print('待投递/等待重试安排在约一年后；人工启用/重投可能改变样本状态。')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except local.LocalError as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(1)
    except Exception as failure:
        print('夹具操作未确认：' + type(failure).__name__ + '；不打印秘密或自动重试。', file=sys.stderr)
        raise SystemExit(1)
