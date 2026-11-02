import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'antd/dist/reset.css';
import { SnapshotPage } from './SnapshotPage';
import './snapshot.css';

const container = document.getElementById('root');
if (!container) throw new Error('展示快照挂载节点缺失');
createRoot(container).render(<StrictMode><SnapshotPage /></StrictMode>);
