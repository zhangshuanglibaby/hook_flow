import { QueryClient, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useSyncExternalStore } from 'react';
import { canRetryRead } from './api/client';
import { getSession, isCurrent, registerSessionCleanup, subscribeSession } from './api/session';

// TanStack Query 负责缓存服务端数据；它不是业务数据的永久存储。
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 30秒内返回缓存，减少页面来回切换时的重复读取。
      staleTime: 30000,
      // 只对安全的 GET 短暂网络/5xx错误重试一次。
      retry: canRetryRead,
      // 不在用户切换窗口时偷偷刷新，避免产生无法解释的后台请求。
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
    },
    mutations: {
      // 创建、编辑、重投等写操作绝不能由浏览器自动重复提交。
      retry: false,
      // 写操作结果不长期留在 mutation 缓存中。
      gcTime: 0,
    },
  },
});

// 退出或切换账号时，取消旧请求并清空旧用户的缓存，防止跨用户泄漏数据。
registerSessionCleanup(() => {
  void queryClient.cancelQueries();
  queryClient.clear();
});

// 把内存会话接入 React；会话变化时，路由和用户区会自动重新渲染。
export function useSession() {
  return useSyncExternalStore(subscribeSession, getSession);
}

// 页面查询 key 使用这个会话编号，实现不同会话之间的缓存隔离。
export function useSessionKey() {
  return useSession()?.generation ?? -1;
}

interface WriteOptions<T, V> {
  mutationFn: (variables: V) => Promise<T>;
  onSuccess?: (data: T, variables: V) => void;
  onError?: (error: Error, variables: V) => void;
}

// 统一封装写操作：防双击、禁止自动重试、成功后失效相关查询缓存。
export function useWrite<T, V>(options: WriteOptions<T, V>, resources: string[]) {
  const generation = useSessionKey();
  const client = useQueryClient();
  // busy 是前端最后一道防双击保护，但真正的幂等和事务仍由后端负责。
  const busy = useRef(false);

  const mutation = useMutation({
    mutationFn: options.mutationFn,
    retry: false,
    gcTime: 0,
    onSuccess: async (data: T, variables: V) => {
      // 旧会话完成的响应不能更新新会话的页面。
      if (!isCurrent(generation)) return;
      // 例如创建事件后，需要重新读取事件列表、概览和审计。
      await Promise.all(resources.map((resource) => client.invalidateQueries({ queryKey: [generation, resource] })));
      if (isCurrent(generation)) options.onSuccess?.(data, variables);
    },
    onError: async (error: Error, variables: V) => {
      if (!isCurrent(generation)) return;
      // 出错后也失效相关缓存，让页面有机会重新读取服务端真实状态。
      await Promise.all(resources.map((resource) => client.invalidateQueries({ queryKey: [generation, resource] })));
      if (isCurrent(generation)) options.onError?.(error, variables);
    },
    onSettled: () => {
      // 成功或失败后都允许下一次明确的用户操作。
      busy.current = false;
    },
  });

  return {
    ...mutation,
    // 页面应该调用 submit，而不是直接调用 mutation.mutate。
    submit: (variables: V) => {
      if (busy.current) return;
      busy.current = true;
      mutation.mutate(variables);
    },
  };
}
