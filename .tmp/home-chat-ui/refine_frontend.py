# 작성자: 김진우 — 시작 홈과 편집 화면의 구조를 Figma에 맞춘다.
from pathlib import Path
r=Path('C:/Users/jinwo/heapy-frontend')
p=r/'src/features/home/HomeDashboard.tsx';s=p.read_text(encoding='utf-8')
s=s.replace('Modal, PanResponder,', 'Modal, PanResponder, Platform,')
s=s.replace('type Props = { active?: boolean;', 'type Props = { active?: boolean; onEditingChange?: (editing: boolean) => void;')
s=s.replace("React.useEffect(() => { const sub", "React.useEffect(() => { _props.onEditingChange?.(screen !== 'home'); }, [screen, _props.onEditingChange]);\n  React.useEffect(() => { if (Platform.OS !== 'android') return; const sub")
s=s.replace('return <View style={s.root}>', "return <View style={[s.root, screen !== 'home' && { backgroundColor: '#F6FBF9' }]}>" )
s=s.replace("{config.metrics.length}개 선택", "{config.metrics.length}개 선택")
# 시작 화면은 별도 카드 두 개를 표시하고 편집·미리보기에는 모듈 컨테이너를 표시한다.
old="if (id === 'metrics') return <View key={id} style={s.card}>"
new="if (id === 'metrics') return <View key={id} style={screen === 'home' ? { gap: 12 } : s.card}>"
s=s.replace(old,new)
s=s.replace("{(screen === 'home' ? saved : draft).modules.map(id => renderModule(id, screen === 'home' ? saved : draft))}", """{(screen === 'home' ? saved : draft).modules.map(id => <React.Fragment key={id}>
          {id === 'mission' && signal && screen === 'home' && <View style={[s.card, s.row]}><View style={{ flex: 1, gap: 6 }}><Text style={s.rowTitle}>확인할 건강 신호 1개</Text><Text style={s.caption}>혈압 기록이 3일 비었어요 · 예시</Text></View><Pressable onPress={() => setSignal(false)} style={s.editButton}><Text style={s.link}>확인</Text></Pressable></View>}
          {id === 'mission' && screen === 'home' && <View style={s.row}><Text style={s.heading}>오늘의 행동</Text><Text style={s.link}>미션 1 / 3</Text></View>}
          {renderModule(id, screen === 'home' ? saved : draft)}</React.Fragment>)}""")
start=s.index('        {signal && <View');end=s.index('\n        {!(',start);s=s[:start]+s[end:]
s=s.replace("page: { padding: 20, gap: 14, paddingBottom: 28 }", "page: { padding: 20, gap: 14, paddingBottom: 28 }")
s=s.replace('<Pressable ', '<Pressable accessibilityRole="button" ').replace('accessibilityRole="button" accessibilityRole="button"','accessibilityRole="button"')
p.write_text(s,encoding='utf-8')
p=r/'src/features/home/HomeScreen.tsx';s=p.read_text(encoding='utf-8').replace("  const [selected,", "  const [homeEditing, setHomeEditing] = useState(false);\n  const [selected,")
s=s.replace("active={selected === 'home'}", "active={selected === 'home'}\n            onEditingChange={setHomeEditing}")
s=s.replace('<View style={styles.tabBar}>', "<View style={[styles.tabBar, selected === 'home' && homeEditing && { display: 'none' }]}>")
p.write_text(s,encoding='utf-8')
p=r/'src/features/home/homeModel.ts';s=p.read_text(encoding='utf-8').replace("['briefing', 'metrics', 'medication', 'mission']", "['briefing', 'metrics', 'mission']");p.write_text(s,encoding='utf-8')
p=r/'src/features/chat/ChatScreen.tsx';s=p.read_text(encoding='utf-8').replace('  Linking,', '  Linking,\n  BackHandler,')
s=s.replace('  const sessionRows =', """  useEffect(() => {
    if (Platform.OS !== 'android') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (view === 'history' || (view === 'partners' && sessionId)) { setView(sessionId ? 'conversation' : 'partners'); return true; }
      return false;
    }); return () => sub.remove();
  }, [view, sessionId]);
  const sessionRows =""")
s=s.replace("if (busy || !question || question.length > 2000) return;", "if (busy || changing || !question || question.length > 2000) return;")
s=s.replace("setInput('');\n      }\n    } catch", "setInput('');\n        requestAnimationFrame(() => messageScroll.current?.scrollToEnd({ animated: true }));\n      }\n    } catch")
s=s.replace("gap: 14, boxShadow: '0px 14px", "gap: 12, boxShadow: '0px 14px")
s=s.replace('<Pressable ', '<Pressable accessibilityRole="button" ').replace('accessibilityRole="button" accessibilityRole="button"','accessibilityRole="button"')
p.write_text(s,encoding='utf-8')
