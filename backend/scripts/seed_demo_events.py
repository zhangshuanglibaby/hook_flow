"""向独立本机开发库追加一批30条虚构事件；正式HTTP，不覆盖历史、不自动重试。"""
import argparse
import datetime
import getpass
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('local_dev', Path(__file__).with_name('local_dev.py'))
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
TYPES = ('order.paid', 'order.created', 'payment.confirmed', 'inventory.reserved', 'shipment.created', 'refund.created')


def event_body(index, endpoint, batch):
    return {'eventType': TYPES[index % len(TYPES)], 'endpointId': endpoint,
            'payload': {'fictional': True, 'demoBatch': batch, 'sequence': index + 1,
                        'orderId': 'DEMO-' + str(index + 1).zfill(4),
                        'amount': 100 + index * 13, 'currency': 'CNY'}}


class Seeder:
    def __init__(self, config, journal):
        self.config = config
        self.path = journal
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), local.NoRedirect())
        self.data = {'batch': uuid.uuid4().hex, 'state': 'STARTED', 'events': [], 'receipts': []}

    def save(self):
        temporary = self.path.with_name(self.path.name + '.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(self.data, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)

    def api(self, method, path, body=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request('http://127.0.0.1:' + str(self.config['port']) + path,
                    data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            rid = response.headers.get('X-Request-Id', '')
            raw = response.read(2 * 1024 * 1024 + 1)
        local.require(status == expected, 'HTTP状态不符：' + str(status) + '；写请求未重试')
        local.require(str(uuid.UUID(rid)) == rid and len(raw) <= 2 * 1024 * 1024, '响应标识或大小无效')
        self.data['receipts'].append({'method': method, 'path': path, 'status': status, 'requestId': rid})
        return (json.loads(raw) if raw else None), rid

    def create(self, username, password):
        result, _ = self.api('POST', '/api/v1/auth/login', {'username': username, 'password': password})
        local.require(bool(result.get('token')), '登录未返回Token')
        self.token = result['token']
        config, _ = self.api('GET', '/api/v1/admin/simulator/config')
        local.require(config['mode'] == 'ALWAYS_200', '模拟器不是ALWAYS_200；为避免干扰其他演示，不自动切换')
        self.data['beforeCount'] = int(local.sql(self.config, 'SELECT COUNT(*) FROM event'))
        self.save()
        endpoint, rid = self.api('POST', '/api/v1/admin/endpoints',
                    {'name': '30条演示事件-' + self.data['batch'][:8], 'targetType': 'SIMULATOR', 'timeoutMs': 5000}, 201)
        local.require(type(endpoint['id']) is int and endpoint['url'] is None and 'signingSecret' not in endpoint,
                      '接收端响应不符')
        self.data['endpointId'] = endpoint['id']
        self.save()
        found = local.sql(self.config, "SELECT COUNT(*) FROM audit_log WHERE request_id='" + rid
                    + "' AND action='ENDPOINT_CREATED' AND object_id='" + str(endpoint['id']) + "'")
        local.require(found == '1', 'HTTP服务与独立开发库不一致；停止创建事件')
        for index in range(30):
            self.data['pendingSequence'] = index + 1
            self.save()
            event, _ = self.api('POST', '/api/v1/admin/events/test', event_body(index, endpoint['id'], self.data['batch']), 202)
            event_id = str(uuid.UUID(event['eventId']))
            local.require(event['duplicate'] is False and event['endpointId'] == endpoint['id'], '事件创建响应不符')
            self.data['events'].append(event_id)
            self.data.pop('pendingSequence')
            self.save()
        deadline = time.monotonic() + 45
        remaining = set(self.data['events'])
        while remaining:
            for eid in tuple(remaining):
                detail, _ = self.api('GET', '/api/v1/admin/events/' + eid)
                local.require(detail['status'] not in ('FINAL_FAILED', 'PAUSED'), '演示投递未成功；不重复提交')
                if detail['status'] == 'SUCCESS':
                    local.require(detail['latestRoundNo'] == 1 and len(detail['rounds']) == 1,
                                  '演示轮次不符')
                    attempt = detail['rounds'][0]['attempts']
                    local.require(len(attempt) == 1 and attempt[0]['result'] == 'SUCCESS'
                                  and attempt[0]['httpStatus'] == 200, '演示实际HTTP结果不符')
                    remaining.remove(eid)
            local.require(not remaining or time.monotonic() < deadline, '投递等待截止；保留已创建事件')
            if remaining:
                time.sleep(0.5)
        endpoint_id = str(endpoint['id'])
        local.require(local.sql(self.config, 'SELECT COUNT(*) FROM event WHERE endpoint_id=' + endpoint_id) == '30',
                      '独立开发库事件数不符')
        page1, _ = self.api('GET', '/api/v1/admin/events?endpointId=' + endpoint_id + '&page=1&size=20')
        page2, _ = self.api('GET', '/api/v1/admin/events?endpointId=' + endpoint_id + '&page=2&size=20')
        ids = [item['eventId'] for page in (page1, page2) for item in page['items']]
        local.require(page1['total'] == page2['total'] == 30 and len(page1['items']) == 20
                      and len(page2['items']) == 10 and set(ids) == set(self.data['events']) and len(set(ids)) == 30,
                      '分页核验失败')
        self.data.update(state='COMPLETE', created=30, successful=30, pagination=[20, 10],
                         finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        self.save()
        print('已追加30条真实演示事件，全部投递成功；分页20+10，原数据保留。')
        print('前端筛选接收端ID：' + endpoint_id + '，或清除筛选后刷新列表。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--username', help='不填则使用私有开发配置的管理员；指定时隐藏输入密码')
    args = parser.parse_args()
    with local.lock('demo-events.lock'):
        config = local.load()
        local.docker_ready()
        container, volume = local.inspect('container', config['container']), local.inspect('volume', config['volume'])
        local.require(container is not None and volume is not None, '独立开发数据库不存在')
        local.owned(container, config, 'container')
        local.owned(volume, config, 'volume')
        local.require(container['State']['Running'], '独立开发数据库未运行')
        path = local.PRIVATE / 'demo-events-30.json'
        local.require(not path.exists() and not path.is_symlink(),
                      '已有本批演示操作记录；不重复添加。若上次部分失败，请先核对已有记录，不删除账本重跑')
        username, password = config['admin_user'], config['admin_password']
        if args.username:
            local.require(sys.stdin.isatty(), '指定账号时请在交互终端隐藏输入密码')
            username, password = args.username, getpass.getpass('管理员密码（隐藏输入）: ')
        seeder = Seeder(config, path)
        try:
            seeder.create(username, password)
        except Exception:
            if path.exists():
                seeder.data['state'] = 'INCOMPLETE'
                seeder.save()
            raise
        finally:
            if seeder.token:
                try:
                    seeder.api('POST', '/api/v1/auth/logout', expected=204)
                except Exception:
                    print('退出响应未确认；短期Token未保存。', file=sys.stderr)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except local.LocalError as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(1)
    except Exception as failure:
        print('创建停止：' + type(failure).__name__ + '；未自动重试或删除已有数据。', file=sys.stderr)
        raise SystemExit(1)
