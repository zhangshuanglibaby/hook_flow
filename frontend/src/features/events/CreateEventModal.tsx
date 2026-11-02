import { App, Button, Form, Input, Modal } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EndpointSelect } from '../../shared/EndpointSelect';
import { api } from '../../shared/api/resources';
import { buildTestEventBody } from '../../shared/api/json';
import { useWrite } from '../../shared/query';
import { ErrorNotice } from '../../shared/ui';
import { jsonError, textError } from '../../shared/format';

// 创建测试事件的弹窗，同时被事件页和故障模拟器复用。
export function CreateEventModal({ onClose, simulator = false }: { onClose: () => void; simulator?: boolean }) {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [form] = Form.useForm();
  // 这里的错误是前端校验错误；后端错误由 mutation.error 提供。
  const [validation, setValidation] = useState<Error | null>(null);
  // 写操作统一使用 useWrite：防双击、不自动重发，并在成功后刷新相关查询。
  const mutation = useWrite({
    mutationFn: api.createEvent,
    onSuccess: (event) => {
      // 202 只表示事件已经被后端受理，详情页会继续读取真实投递状态。
      void message.success('事件已受理，投递结果请查看最新轮状态。');
      onClose();
      navigate(`/events/${encodeURIComponent(event.eventId)}`);
    },
  }, ['events', 'overview', 'audit']);

  // 表单通过后先构造真实请求体，再交给请求层发送。
  function submit(values: { eventType: string; endpointId: number; payload: string }) {
    try {
      const body = buildTestEventBody(values.eventType, values.endpointId, values.payload);
      setValidation(null);
      mutation.submit(body);
    } catch (error) {
      setValidation(error instanceof Error ? error : new Error('请检查输入。'));
    }
  }
  return <Modal title={simulator ? '生成模拟器测试事件' : '创建测试事件'} open onCancel={() => { if (!mutation.isPending) onClose(); }} maskClosable={false} keyboard={!mutation.isPending} closable={!mutation.isPending} footer={<div className="modal-actions"><Button disabled={mutation.isPending} onClick={onClose}>取消</Button><Button type="primary" loading={mutation.isPending} disabled={Boolean(mutation.error && 'uncertain' in mutation.error && mutation.error.uncertain)} onClick={() => form.submit()}>创建测试事件</Button></div>}>
    <p className="subtle">平台生成 UUID。仅使用虚构数据；202 表示已落库受理，不是投递成功。</p><ErrorNotice error={validation ?? mutation.error} /><Form form={form} layout="vertical" onFinish={submit} disabled={mutation.isPending} initialValues={{ eventType: simulator ? 'demo.simulator' : '', payload: '{\n  "fictional": true\n}' }}>
      <Form.Item label="事件类型" name="eventType" rules={[{ validator: async (_, value: string) => { const error = textError(value ?? '', 128); if (error || !value.trim()) throw new Error(error ?? '不能全为空白。'); } }]}><Input placeholder="如 order.paid" /></Form.Item>
      <Form.Item label="接收端" name="endpointId" rules={[{ required: true, message: '请选择启用的接收端。' }]}><EndpointSelect creation simulator={simulator} /></Form.Item>
      <Form.Item label="事件内容（JSON）" name="payload" rules={[{ validator: async (_, value: string) => { const error = jsonError(value ?? ''); if (error) throw new Error(error); } }]} extra="支持任意 JSON 值与高精度数字。完整 UTF-8 请求上限 256 KiB。"><Input.TextArea className="mono" rows={8} spellCheck={false} /></Form.Item>
    </Form>{mutation.error && 'uncertain' in mutation.error && mutation.error.uncertain ? <p>先关闭弹窗并查询近期事件。管理员测试入口不能携带自定义幂等 ID，请勿直接重发。</p> : null}
  </Modal>;
}
