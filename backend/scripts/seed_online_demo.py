"""通过服务器本机管理员 API 追加真实演示数据；不读取数据库凭据，不删除历史。"""
import argparse
from collections import Counter
import datetime as dt
import fcntl
import getpass
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = '/api/v1'
TYPES = ('order.paid', 'inventory.reserved', 'payment.confirmed',
         'order.created', 'purchase.created', 'shipment.created')
GROUPS = ('direct', 'retry', 'failed', 'redelivered', 'paused')
NAMES = {'direct': '积分服务', 'retry': '库存服务', 'failed': '内置故障模拟器',
         'redelivered': '订单服务', 'paused': '采购服务'}
SCENARIOS = {'direct': '首次成功', 'retry': '自动重试后成功', 'failed': '最终失败',
             'redelivered': '人工重投后成功', 'paused': '暂停待恢复'}
CONFIG_FIELDS = ('mode', 'failFirstN', 'delayMs')


class DemoError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DemoError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def event_body(group, index, endpoint_id, batch):
    return {'eventType': TYPES[index % len(TYPES)], 'endpointId': endpoint_id,
            'payload': {'fictional': True, 'demoBatch': batch,
                        'scenario': SCENARIOS[group], 'sequence': index + 1,
                        'orderNo': 'DEMO-' + batch + '-' + group + '-' + str(index + 1).zfill(3),
                        'userId': 'demo-user-' + str(index + 1).zfill(3),
                        'amount': 99 + index * 20, 'points': 99 + index * 20,
                        'currency': 'CNY'}}


def open_state(directory):
    require(not directory.is_symlink(), '账本目录不能是符号链接。')
    directory.mkdir(mode=0o700, parents=False, exist_ok=True)
    info = directory.stat()
    require(info.st_uid == os.getuid() and stat.S_ISDIR(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o700,
            '账本目录必须属于当前用户，且权限为700。')
    fd = os.open(directory / 'run.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        raise DemoError('同一账本已有脚本在运行。') from None
    return fd


class Seeder:
    def __init__(self, port, path, per_group):
        self.base = 'http://127.0.0.1:' + str(port)
        self.path = path
        self.per_group = per_group
        self.token = None
        self.owned_config = None
        self.original_config = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.data = {'batch': dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
                     + '-' + uuid.uuid4().hex[:8], 'state': 'STARTED',
                     'endpoints': {}, 'events': [], 'writes': []}

    def save(self):
        fd, name = tempfile.mkstemp(prefix='.record-', dir=self.path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(self.data, stream, ensure_ascii=False, indent=2)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, self.path)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    def api(self, method, path, body=None, expected=200):
        require(path.startswith(API + '/'), '仅允许调用固定的 HookFlow API。')
        writing = method != 'GET'
        operation = None
        if writing:
            operation = {'method': method, 'path': path, 'state': 'UNCONFIRMED'}
            self.data['writes'].append(operation)
            self.save()
        headers = {'Accept': 'application/json', 'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                    data=None if body is None else json.dumps(body).encode('utf-8'))
        try:
            try:
                response = self.opener.open(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                rid = response.headers.get('X-Request-Id', '')
                raw = response.read(2 * 1024 * 1024 + 1)
            if operation is not None:
                operation.update(httpStatus=status, requestId=rid, state='RESPONSE_RECEIVED')
                self.save()
            require(status == expected,
                    f'{method} {path} 返回 HTTP {status}，期望 {expected}；没有自动重试。')
            require(len(raw) <= 2 * 1024 * 1024 and bool(rid), '响应过大或缺少请求标识。')
            result = json.loads(raw) if raw else None
            if operation is not None:
                operation['state'] = 'CONFIRMED'
                self.save()
            return result
        except (OSError, ValueError, urllib.error.URLError):
            raise DemoError(f'{method} {path} 连接/响应异常；写入是否生效可能未知，禁止盲目重跑。') from None

    def detail(self, event):
        return self.api('GET', API + '/admin/events/' + urllib.parse.quote(event['eventId'], safe=''))

    def configuration(self):
        return self.api('GET', API + '/admin/simulator/config')

    def assert_owned(self):
        require(self.configuration() == self.owned_config,
                '模拟器配置被其他操作者修改，已停止；不会覆盖他人的配置。')

    def set_mode(self, mode, failures=0):
        self.assert_owned()
        self.owned_config = self.api('PUT', API + '/admin/simulator/config',
                                    {'mode': mode, 'failFirstN': failures, 'delayMs': 0})
        self.data['lastOwnedConfiguration'] = self.owned_config
        self.save()

    def endpoint(self, group):
        endpoint = self.api('POST', API + '/admin/endpoints',
                    {'name': NAMES[group] + '-' + SCENARIOS[group] + '-' + self.data['batch'],
                     'targetType': 'SIMULATOR', 'timeoutMs': 5000, 'enabled': True}, 201)
        require(type(endpoint.get('id')) is int and endpoint.get('targetType') == 'SIMULATOR'
                and endpoint.get('url') is None and endpoint.get('enabled') is True,
                '接收端响应不符合内置模拟器契约。')
        self.data['endpoints'][group] = endpoint['id']
        self.save()

    def create_group(self, group):
        self.assert_owned()
        for index in range(self.per_group):
            event = self.api('POST', API + '/admin/events/test',
                    event_body(group, index, self.data['endpoints'][group], self.data['batch']), 202)
            eid = event.get('eventId')
            require(isinstance(eid, str) and str(uuid.UUID(eid)) == eid
                    and event.get('duplicate') is False
                    and event.get('endpointId') == self.data['endpoints'][group],
                    '事件响应异常；停止，不重发管理员测试请求。')
            self.data['events'].append({'eventId': eid, 'group': group})
            self.save()

    def events(self, groups):
        return [event for event in self.data['events'] if event['group'] in groups]

    def verify(self, detail, state, rounds, statuses):
        require(detail.get('status') == state and detail.get('latestRoundNo') == len(rounds),
                '事件状态或最新轮次不符合预期。')
        history = detail.get('rounds', [])
        require(len(history) == len(rounds), '轮次历史数量不符合预期。')
        for number, (row, count, codes) in enumerate(zip(history, rounds, statuses), 1):
            attempts = row.get('attempts', [])
            require(row.get('roundNo') == number and row.get('maxAttempts') == 3
                    and row.get('attemptCount') == count and len(attempts) == count
                    and [item.get('httpStatus') for item in attempts] == codes,
                    '真实尝试记录不符合默认3次/HTTP响应预期；可能存在并发配置修改。')
            require(row.get('status') == (state if number == len(rounds) else 'FINAL_FAILED'),
                    '旧轮次或最新轮状态异常。')
            for item in attempts:
                require(item.get('result') == ('SUCCESS' if item['httpStatus'] == 200 else 'HTTP_FAILURE'),
                        '尝试结果分类不符合预期。')

    def wait(self, groups, state, rounds, statuses, timeout=180):
        pending = {event['eventId']: event for event in self.events(groups)}
        deadline = time.monotonic() + timeout
        while pending:
            self.assert_owned()
            for eid, event in list(pending.items()):
                detail = self.detail(event)
                require(detail['rounds'][-1]['maxAttempts'] == 3,
                        '当前服务每轮尝试上限不是3，停止；不修改服务重试配置。')
                if detail['status'] == state:
                    self.verify(detail, state, rounds, statuses)
                    event.update(observedStatus=state, observedRound=detail['latestRoundNo'])
                    self.save()
                    del pending[eid]
                else:
                    require(detail['status'] not in ('SUCCESS', 'FINAL_FAILED', 'PAUSED'),
                            '事件提前进入非预期终态/暂停态，停止以保留现场。')
            require(not pending or time.monotonic() < deadline,
                    '等待状态超时；已有记录保留，不重复提交。')
            if pending:
                time.sleep(0.5)

    def pause_group(self):
        self.create_group('paused')
        # 停用端点会拒绝创建事件，须先获得真实的首次500，再暂停待重试任务。
        self.wait({'paused'}, 'WAIT_RETRY', [1], [[500]], timeout=30)
        endpoint_id = self.data['endpoints']['paused']
        endpoint = self.api('POST', API + f'/admin/endpoints/{endpoint_id}/disable')
        require(endpoint.get('enabled') is False, '暂停专用接收端未停用。')
        self.wait({'paused'}, 'PAUSED', [1], [[500]], timeout=30)

    def preflight(self):
        for state in ('PENDING', 'DELIVERING', 'WAIT_RETRY'):
            page = self.api('GET', API + '/admin/events?' + urllib.parse.urlencode(
                {'status': state, 'page': 1, 'size': 1}))
            require(page.get('total') == 0,
                    '存在其他活跃事件（' + state + '），请先等其结束；未修改模拟器或创建事件。')
        self.original_config = self.configuration()
        self.owned_config = self.original_config
        self.data['originalConfiguration'] = self.original_config
        self.save()

    def restore(self):
        if self.original_config is None:
            return
        current = self.configuration()
        if current != self.owned_config:
            self.data['restoration'] = 'SKIPPED_CONCURRENT_CHANGE'
            self.save()
            raise DemoError('模拟器配置已被他人修改，未自动覆盖。原配置保存在账本中。')
        body = {field: self.original_config[field] for field in CONFIG_FIELDS}
        self.api('PUT', API + '/admin/simulator/config', body)
        self.data['restoration'] = 'RESTORED'
        self.save()

    def pause_incomplete(self):
        for endpoint_id in self.data['endpoints'].values():
            try:
                self.api('POST', API + f'/admin/endpoints/{endpoint_id}/disable')
            except Exception:
                self.data.setdefault('cleanupUnconfirmedEndpoints', []).append(endpoint_id)
        self.save()

    def run(self):
        self.preflight()
        print('开始创建专用模拟器接收端；现有事件和接收端不会被修改。', flush=True)
        for group in GROUPS:
            self.endpoint(group)
        self.set_mode('ALWAYS_200')
        self.create_group('direct')
        self.wait({'direct'}, 'SUCCESS', [1], [[200]])
        print('首次成功组已核验。', flush=True)
        self.set_mode('FAIL_FIRST_N', 1)
        self.create_group('retry')
        self.wait({'retry'}, 'SUCCESS', [2], [[500, 200]])
        print('自动重试成功组已核验。开始生成失败历史，预计等待约45秒。', flush=True)
        self.set_mode('ALWAYS_500')
        self.create_group('failed')
        self.create_group('redelivered')
        self.wait({'failed', 'redelivered'}, 'FINAL_FAILED', [3], [[500, 500, 500]])
        self.set_mode('FAIL_FIRST_N', 1)
        self.pause_group()
        print('最终失败和暂停组已核验。开始人工新轮投递。', flush=True)
        self.set_mode('ALWAYS_200')
        for event in self.events({'redelivered'}):
            self.api('POST', API + '/admin/events/' + event['eventId'] + '/redeliver', expected=202)
        self.wait({'redelivered'}, 'SUCCESS', [3, 1], [[500, 500, 500], [200]])
        for event in self.data['events']:
            detail = self.detail(event)
            expected = 'PAUSED' if event['group'] == 'paused' else (
                'FINAL_FAILED' if event['group'] == 'failed' else 'SUCCESS')
            require(detail['status'] == expected, '最终复查状态改变；未宣称整批成功。')
        self.verify_pages()
        self.data['state'] = 'VERIFIED'
        self.save()

    def verify_pages(self):
        for group, endpoint_id in self.data['endpoints'].items():
            found = []
            page_no = 1
            while True:
                page = self.api('GET', API + '/admin/events?' + urllib.parse.urlencode(
                    {'endpointId': endpoint_id, 'page': page_no, 'size': 20}))
                require(page['total'] == self.per_group, '本批接收端记录数量改变，可能有并发创建。')
                found.extend(item['eventId'] for item in page['items'])
                if len(found) >= page['total']:
                    break
                require(bool(page['items']) and page_no < 10, '分页响应不符合预期。')
                page_no += 1
            require(len(found) == self.per_group and set(found) == {
                event['eventId'] for event in self.events({group})}, '分页核验失败。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8080, help='服务器本机后端端口，默认8080')
    parser.add_argument('--username', default='admin123')
    parser.add_argument('--per-group', type=int, choices=range(1, 7), default=6,
                        metavar='1..6', help='每场景1至6条，默认6，共30条')
    parser.add_argument('--state-dir', type=Path, default=Path.home() / '.hookflow-demo-seed',
                        help='权限700的操作账本目录；已有账本时拒绝再次创建')
    parser.add_argument('--apply', action='store_true', help='实际执行；不传此参数只显示计划')
    parser.add_argument('--status', action='store_true', help='只查看本机账本摘要，不访问服务')
    args = parser.parse_args()
    require(1 <= args.port <= 65535, '端口无效。')
    if args.status:
        data = json.loads((args.state_dir / 'record.json').read_text(encoding='utf-8'))
        print('批次：' + data['batch'] + '，状态：' + data['state'])
        print('事件数：' + str(len(data['events'])))
        print('已记录状态：' + json.dumps(dict(Counter(
            item.get('observedStatus', 'UNVERIFIED') for item in data['events'])), ensure_ascii=False))
        print('模拟器恢复状态：' + data.get('restoration', '未确认'))
        return 0
    print(f'计划：仅调用127.0.0.1:{args.port}，新增5个SIMULATOR接收端、{args.per_group * 5}条虚构事件。')
    print('五组：首次成功、重试成功、最终失败、人工新轮成功、暂停中。')
    print('待投递/等待重试仅为运行中瞬时状态，不承诺冻结；不写SQL，不轮换业务Key，不删除原数据。')
    print('会临时修改全局模拟器；请先备份，并确保无人同时演示/修改模拟器。')
    if not args.apply:
        print('尚未执行。实际运行需加 --apply，并在终端确认。')
        return 0
    require(sys.stdin.isatty() and sys.stderr.isatty(), '请在交互终端运行，不通过管道传密码。')
    require(input('确认是虚构数据沙盒、已备份且当前独占模拟器？输入 SEED 确认：') == 'SEED', '已取消。')
    password = getpass.getpass('网站管理员密码（隐藏输入，不是MySQL密码）：')
    require(bool(password), '密码不能为空。')
    lock_fd = open_state(args.state_dir)
    seed = None
    try:
        path = args.state_dir / 'record.json'
        require(not path.exists() and not path.is_symlink(),
                '已有操作账本，拒绝重复创建。用 --status 查看；不要删除账本后盲目重跑。')
        seed = Seeder(args.port, path, args.per_group)
        seed.save()
        login = seed.api('POST', API + '/auth/login', {'username': args.username, 'password': password})
        require(isinstance(login.get('token'), str) and bool(login['token']), '登录响应无有效Token。')
        seed.token = login['token']
        password = None
        try:
            seed.run()
        except BaseException:
            seed.pause_incomplete()
            raise
        finally:
            seed.restore()
        seed.data['state'] = 'COMPLETE'
        seed.save()
        print('已完成：' + str(args.per_group * 5) + '条真实事件，原数据保留，模拟器配置已恢复。')
        for group in GROUPS:
            print(SCENARIOS[group] + '：' + str(args.per_group) + '条，接收端ID=' + str(seed.data['endpoints'][group]))
        print('账本：' + str(path))
        print('浏览器清除筛选并刷新。暂停组端点保持停用，启用后会继续投递。')
        print('终态事件仍受后端30天清理规则约束；本次没有测试进程崩溃恢复。')
        return 0
    except BaseException:
        if seed is not None:
            seed.data['state'] = 'INCOMPLETE'
            seed.save()
        raise
    finally:
        if seed is not None and seed.token:
            try:
                seed.api('POST', API + '/auth/logout', expected=204)
            except Exception:
                print('警告：退出未确认，短期Token未保存，不要发布日志中的凭证。', file=sys.stderr)
        os.close(lock_fd)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (DemoError, OSError, ValueError) as failure:
        message = str(failure) if isinstance(failure, DemoError) else type(failure).__name__
        print('停止：' + message, file=sys.stderr)
        print('已有记录保留。先看 --status 和账本，不自动重试写请求或删除数据。', file=sys.stderr)
        raise SystemExit(1)
    except KeyboardInterrupt:
        print('已中断；请核对账本及模拟器配置，不盲目重跑。', file=sys.stderr)
        raise SystemExit(130)
    except Exception as failure:
        print('停止：' + type(failure).__name__ + '；未输出响应正文或凭证，请检查账本。', file=sys.stderr)
        raise SystemExit(1)
