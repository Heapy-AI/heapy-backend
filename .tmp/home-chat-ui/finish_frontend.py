# 작성자: 김진우
from pathlib import Path
import json
r=Path('C:/Users/jinwo/heapy-frontend')
p=r/'src/features/home/homeModel.ts';s=p.read_text(encoding='utf-8').replace('const result = [...items];', 'if (from < 0 || from >= items.length) return [...items]; const result = [...items];').replace('0, item);','0, item!);');p.write_text(s,encoding='utf-8')
p=r/'src/features/home/HomeDashboard.tsx';s=p.read_text(encoding='utf-8').replace("import LinearGradient", "import { SvgXml } from 'react-native-svg';\nimport LinearGradient")
svg=Path('C:/Users/jinwo/heapy-backend/.tmp/home-chat-ui/settings.svg').read_text(encoding='utf-8')
s=s.replace('type Props = {', 'const settingsIcon = '+json.dumps(svg)+';\ntype Props = { active?: boolean;')
s=s.replace("<Text style={s.small}>{configurable ? '⚙' : ''}</Text>", '<SvgXml xml={configurable ? settingsIcon : \'<svg xmlns="http://www.w3.org/2000/svg"/>\'} width={16} height={16} />')
s=s.replace("if (screen === 'home' && !detail) return false;", "if (_props.active === false || (screen === 'home' && !detail)) return false;")
p.write_text(s,encoding='utf-8')
p=r/'src/features/home/HomeScreen.tsx';s=p.read_text(encoding='utf-8').replace('<HomeDashboard\n', "<HomeDashboard\n            active={selected === 'home'}\n");p.write_text(s,encoding='utf-8')
p=r/'preview/mockClient.ts';s=p.read_text(encoding='utf-8');s+='''
// 작성자: 김진우 — 브라우저 디자인 검증 전용 합성 상담. 실제 네트워크를 호출하지 않는다.
type Session = { sessionId: string; title: string; companionCode: string; createdAt: string };
const sessions: Session[] = [];
const messages: Record<string, any[]> = {};
const response = (data: any) => Promise.resolve({ data });
export const apiClient = {
  get: async (url: string) => {
    if (url === '/api/chat/sessions') return response({ items: [...sessions], nextCursor: null });
    const id = url.split('/')[4];
    if (url.endsWith('/messages')) return response({ items: messages[id!] ?? [], nextCursor: null });
    if (id) return response(sessions.find(x => x.sessionId === id));
    throw new Error('미리보기에서 정의하지 않은 API입니다.');
  },
  post: async (url: string, body: any) => {
    if (url === '/api/chat/sessions') { const session = { sessionId: `synthetic-${sessions.length + 1}`, title: '합성 상담', companionCode: body.companionCode, createdAt: new Date().toISOString() }; sessions.unshift(session); return response(session); }
    const id = url.split('/')[4]!; const session = sessions.find(x => x.sessionId === id)!;
    const rows = messages[id] ??= []; const n = rows.length;
    rows.push({ messageId: `u${n}`, role: 'user', content: body.message, responseStatus: 'completed', citations: [] }, { messageId: `a${n}`, role: 'assistant', companionCodeSnapshot: session.companionCode, content: '화면 검증용 합성 답변입니다.', responseStatus: 'completed', citations: [{ sourceTitle: '합성 근거', sourceUrl: 'https://example.org' }] });
    return response('event: done\\ndata: {}\\n\\n');
  },
  patch: async (url: string, body: any) => { const session = sessions.find(x => x.sessionId === url.split('/')[4])!; Object.assign(session, body); return response(session); },
  delete: async (url: string) => { const id = url.split('/')[4]; const index = sessions.findIndex(x => x.sessionId === id); if (index >= 0) sessions.splice(index, 1); delete messages[id!]; return response(null); },
};
''';p.write_text(s,encoding='utf-8')
