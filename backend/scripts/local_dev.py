"""本机持久开发环境；不复用、重置或删除其他数据库。"""
import argparse
import base64
import contextlib
import fcntl
import getpass
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BACKEND = Path(__file__).resolve().parents[1]
PRIVATE = BACKEND.parents[1] / '.hookflow-local'
IMAGE = 'mysql:8.4.13'
LABEL = 'hookflow.local.owner'


class LocalError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise LocalError(message)


def call(args, env=None, timeout=30, allow_failure=False):
    try:
        result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise LocalError('所需程序不存在：' + Path(args[0]).name) from None
    except subprocess.TimeoutExpired:
        raise LocalError('命令超时：' + Path(args[0]).name) from None
    if not allow_failure:
        require(result.returncode == 0, '命令失败：' + Path(args[0]).name + '；原始输出不显示以保护凭据')
    return result


def private_directory():
    require(not PRIVATE.is_symlink(), '私有目录不能是符号链接')
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    info = PRIVATE.stat()
    require(info.st_uid == os.getuid() and stat.S_ISDIR(info.st_mode), '私有目录归属无效')
    require(stat.S_IMODE(info.st_mode) == 0o700, '私有目录权限必须为700；不自动修改已有目录权限')


@contextlib.contextmanager
def lock(name):
    private_directory()
    fd = os.open(PRIVATE / name, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        require(info.st_uid == os.getuid() and stat.S_ISREG(info.st_mode)
                and stat.S_IMODE(info.st_mode) == 0o600, '锁文件归属或权限无效')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise LocalError('已有同类开发操作运行，请勿重复启动') from None
        yield
    finally:
        os.close(fd)


def validate(config):
    require(config.get('version') == 1, '本机配置版本无效')
    owner = config.get('owner', '')
    require(len(owner) == 32 and all(c in '0123456789abcdef' for c in owner), '环境标记无效')
    require(config.get('container') == 'hookflow-local-' + owner[:12], '容器名与归属不符')
    require(config.get('volume') == 'hookflow-local-data-' + owner, '数据卷名与归属不符')
    require(config.get('state') in ('new', 'volume-created', 'ready'), '环境状态无效')
    require(config.get('database') == 'hookflow_dev' and config.get('db_user') == 'hookflow_dev', '开发库配置无效')
    for field in ('db_password', 'root_password'):
        value = config.get(field, '')
        require(len(value) == 43 and all(c.isalnum() or c in '_-' for c in value), '本机数据库凭据格式无效')
    user, password = config.get('admin_user', ''), config.get('admin_password', '')
    require(isinstance(user, str) and user.strip() and len(user) <= 64, '管理员用户名无效')
    require(isinstance(password, str) and password.strip() and len(password) <= 1024, '管理员密码无效')
    try:
        encoded = config['master_key']
        raw = base64.b64decode(encoded, validate=True)
        require(len(raw) == 32 and base64.b64encode(raw).decode() == encoded, '主密钥格式无效')
    except (KeyError, ValueError, TypeError):
        raise LocalError('主密钥格式无效') from None
    require(type(config.get('port')) is int and 1024 <= config['port'] <= 65535, '后端端口无效')


def load():
    path = PRIVATE / 'config.json'
    require(path.exists() and not path.is_symlink(), '尚未初始化，请先执行 init')
    info = path.stat()
    require(info.st_uid == os.getuid() and stat.S_ISREG(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o600, '配置文件必须归当前用户所有，且权限为600')
    require(info.st_size <= 32768, '配置文件异常过大')
    try:
        config = json.loads(path.read_text())
    except (ValueError, OSError):
        raise LocalError('无法读取本机配置；不覆盖或重建') from None
    validate(config)
    return config


def save(config):
    validate(config)
    path = PRIVATE / 'config.json'
    require(not path.is_symlink(), '配置文件不能是符号链接')
    temporary = PRIVATE / ('config-' + uuid.uuid4().hex + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(config, stream, ensure_ascii=False)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def inspect(kind, name):
    result = call(['docker', kind, 'inspect', name], allow_failure=True)
    if result.returncode:
        return None
    return json.loads(result.stdout)[0]


def docker_ready():
    call(['docker', 'info', '--format', '{{.ServerVersion}}'], timeout=15)


def owned(resource, config, kind):
    labels = resource.get('Labels', {}) if kind == 'volume' else resource.get('Config', {}).get('Labels', {})
    require((labels or {}).get(LABEL) == config['owner'], '资源归属不符，拒绝使用')
    if kind == 'container':
        require(resource['Config']['Image'] == IMAGE, '现有开发容器镜像不符')
        mounts = [m for m in resource.get('Mounts', []) if m.get('Destination') == '/var/lib/mysql']
        require(len(mounts) == 1 and mounts[0].get('Name') == config['volume'], '数据库持久卷不符')


def sql(config, query):
    env = dict(os.environ, MYSQL_PWD=config['db_password'])
    return call(['docker', 'exec', '-e', 'MYSQL_PWD', config['container'], 'mysql',
                 '--protocol=TCP', '-h127.0.0.1', '--user=' + config['db_user'],
                 '--database=' + config['database'], '-N', '-B', '-e', query], env, 10).stdout.strip()


def ensure_database(config, create=False):
    docker_ready()
    volume = inspect('volume', config['volume'])
    container = inspect('container', config['container'])
    if volume is None:
        require(create and config['state'] == 'new' and container is None,
                '原开发卷缺失；为防数据丢失，不自动创建空卷替代')
        call(['docker', 'volume', 'create', '--label', LABEL + '=' + config['owner'], config['volume']])
        volume = inspect('volume', config['volume'])
    owned(volume, config, 'volume')
    if config['state'] == 'new':
        config['state'] = 'volume-created'
        save(config)
    if container is None:
        require(create, '开发容器不存在；执行 init 可基于原持久卷恢复容器')
        call(['docker', 'image', 'inspect', IMAGE])
        env = dict(os.environ, MYSQL_ROOT_PASSWORD=config['root_password'],
                   MYSQL_DATABASE=config['database'], MYSQL_USER=config['db_user'],
                   MYSQL_PASSWORD=config['db_password'])
        call(['docker', 'create', '--pull=never', '--name', config['container'],
              '--label', LABEL + '=' + config['owner'], '--restart', 'unless-stopped',
              '-p', '127.0.0.1::3306', '--mount', 'type=volume,source=' + config['volume'] + ',target=/var/lib/mysql',
              '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE', '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD', IMAGE], env, 60)
        container = inspect('container', config['container'])
    owned(container, config, 'container')
    if not container['State']['Running']:
        call(['docker', 'start', config['container']], timeout=60)
    deadline = time.monotonic() + 90
    while True:
        try:
            require(sql(config, 'SELECT 1') == '1', '数据库未就绪')
            break
        except LocalError:
            if time.monotonic() >= deadline:
                raise LocalError('开发库90秒内未就绪；保留配置与卷，不重置凭据') from None
            time.sleep(1)
    config['state'] = 'ready'
    save(config)
    current = inspect('container', config['container'])
    bindings = current.get('NetworkSettings', {}).get('Ports', {}).get('3306/tcp') or []
    require(len(bindings) == 1 and bindings[0]['HostIp'] == '127.0.0.1', '数据库只允许本机IPv4绑定')
    return int(bindings[0]['HostPort'])


def initialize(port):
    require(sys.stdin.isatty(), '初始化需要交互终端；不能通过聊天或管道传入密码')
    with lock('setup.lock'):
        if (PRIVATE / 'config.json').exists():
            config = load()
            print('已有本机配置，沿用原主密钥/管理员，不覆盖密码。', flush=True)
        else:
            docker_ready()
            call(['docker', 'image', 'inspect', IMAGE])
            user = input('开发管理员用户名（默认 local-admin）: ') or 'local-admin'
            password = getpass.getpass('开发管理员密码（隐藏输入）: ')
            confirm = getpass.getpass('再次输入密码（隐藏输入）: ')
            require(password == confirm, '两次密码不一致；未保存配置')
            require(password.strip() and len(password.encode('utf-8')) <= 72,
                    '开发管理员密码须为非全空白且不超过72个UTF-8字节；未保存配置')
            owner = uuid.uuid4().hex
            config = dict(version=1, owner=owner, container='hookflow-local-' + owner[:12],
                          volume='hookflow-local-data-' + owner, state='new', database='hookflow_dev',
                          db_user='hookflow_dev', db_password=secrets.token_urlsafe(32),
                          root_password=secrets.token_urlsafe(32), admin_user=user, admin_password=password,
                          master_key=base64.b64encode(secrets.token_bytes(32)).decode(), port=port)
            save(config)
        db_port = ensure_database(config, create=True)
        print('开发数据库已就绪；原数据库未操作。', flush=True)
        print('库名：hookflow_dev；用户名：hookflow_dev；本机数据库端口：' + str(db_port), flush=True)
        print('稳定配置保存于仓库外私有目录；请勿删除或上传该目录。', flush=True)
        print('下一步执行 run 启动后端；管理员密码仍为你刚才输入的值。', flush=True)


def application_environment(config, db_port):
    env = dict(os.environ)
    for key in tuple(env):
        if key.startswith(('DB_', 'HOOKFLOW_', 'SPRING_', 'MYSQL_', 'MVNW_')) or key in (
                'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS'):
            env.pop(key)
    env.update(DB_URL='jdbc:mysql://127.0.0.1:' + str(db_port) + '/' + config['database']
               + '?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false',
               DB_USERNAME=config['db_user'], DB_PASSWORD=config['db_password'],
               HOOKFLOW_ADMIN_USERNAME=config['admin_user'], HOOKFLOW_ADMIN_PASSWORD=config['admin_password'],
               HOOKFLOW_SECRET_ENCRYPTION_KEY=config['master_key'], HOOKFLOW_REDIS_ENABLED='false',
               HOOKFLOW_MAX_ATTEMPTS='3', HOOKFLOW_RETRY_DELAYS='10s,30s')
    return env


def run():
    with lock('backend.lock'):
        config = load()
        wrapper = BACKEND / 'mvnw'
        require(wrapper.is_file() and os.access(wrapper, os.X_OK), '项目Maven Wrapper不可执行')
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', config['port']))
            except OSError:
                raise LocalError('后端端口已占用；不停止其他进程') from None
        db_port = ensure_database(config)
        env = application_environment(config, db_port)
        print('启动本机开发后端 http://127.0.0.1:' + str(config['port']) + '；数据库/配置退出后保留。', flush=True)
        child = subprocess.Popen([str(wrapper), '-o', '-f', str(BACKEND / 'pom.xml'), 'spring-boot:run',
                '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=' + str(config['port'])
                + ' --spring.profiles.active=default --hookflow.redis.enabled=false'],
                cwd=BACKEND, env=env, start_new_session=True)
        def stop(*_):
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
        handlers = {s: signal.signal(s, stop) for s in (signal.SIGTERM, signal.SIGINT)}
        try:
            return child.wait()
        finally:
            stop()
            for sig, handler in handlers.items():
                signal.signal(sig, handler)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def status():
    with lock('setup.lock'):
        config = load()
        docker_ready()
        volume, container = inspect('volume', config['volume']), inspect('container', config['container'])
        if volume:
            owned(volume, config, 'volume')
        if container:
            owned(container, config, 'container')
        print('配置：存在，权限600；主密钥不显示。')
        print('开发容器：' + config['container'])
        print('持久卷：' + ('存在' if volume else '缺失，请勿自动重建'))
        print('数据库：' + ('运行中' if container and container['State']['Running'] else '未运行'))
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open('http://127.0.0.1:' + str(config['port']) + '/actuator/health', timeout=5) as response:
                healthy = response.status == 200 and json.loads(response.read(8192)) == {'status': 'UP'} and bool(response.headers.get('X-Request-Id'))
            print('后端健康：' + ('UP' if healthy else '未确认'))
        except (urllib.error.URLError, ValueError, TimeoutError):
            print('后端健康：未运行或不可达')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('init', 'run', 'status'))
    parser.add_argument('--port', type=int, default=8080, help='仅首次init生效')
    args = parser.parse_args()
    require(1024 <= args.port <= 65535, '端口范围无效')
    if args.action == 'init':
        initialize(args.port)
    elif args.action == 'run':
        return run()
    else:
        status()
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except LocalError as error:
        print('本机开发环境：' + str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, TypeError):
        print('本机开发环境操作失败；敏感错误原文不显示，未执行自动删除/重置。', file=sys.stderr)
        raise SystemExit(1)
