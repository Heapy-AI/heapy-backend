# 작성자: 김진우 — 기존 전송·멱등성 처리를 유지하며 상담 화면을 확장한다.
from pathlib import Path
root = Path('C:/Users/jinwo/heapy-frontend')
p = root / 'src/features/chat/ChatScreen.tsx'
s = p.read_text(encoding='utf-8')
s = s.replace('  ActivityIndicator,', '  ActivityIndicator,\n  Alert,\n  Linking,')
s = s.replace("import { colors }", "import LinearGradient from 'react-native-linear-gradient';\nimport { CompanionAvatar, CompanionCode } from './CompanionAvatar';\nimport { colors }")
s = s.replace("type Session = { sessionId: string; title: string; createdAt: string };", "type Session = { sessionId: string; title: string; createdAt: string; companionCode: CompanionCode };\nconst partners = { heapy_cat: { name: '히피냥', tag: '솔직한 생활 코치', description: '친절하지만\\n할 말은 콕 집어.', features: '• 현실적인 습관 코칭\\n• 다정한 잔소리' }, heapy_dog: { name: '히피멍', tag: '소심한 건강 박사', description: '조금 엉뚱해도\\n건강지식은 빠삭.', features: '• 수치·근거에 강함\\n• 차근차근 설명' } };")
s = s.replace('  messageId: string;', '  messageId: string;\n  companionCodeSnapshot?: CompanionCode;')
s = s.replace("  const client = useQueryClient();", """  const client = useQueryClient();
  const [view, setView] = useState<'partners' | 'history' | 'conversation'>('partners');
  const [companion, setCompanion] = useState<CompanionCode>('heapy_cat');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<CompanionCode | 'all'>('all');
  const [changing, setChanging] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Session>();
  const messageScroll = useRef<ScrollView>(null);""")
start = s.index('  const sessions = useQuery(')
end = s.index('  const messages = useInfiniteQuery(', start)
s = s[:start] + """  const sessions = useInfiniteQuery({
    queryKey: ['chat-sessions'], initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => (await apiClient.get<Page<Session>>('/api/chat/sessions', { params: { cursor: pageParam, limit: 30 } })).data,
    getNextPageParam: page => page.nextCursor ?? undefined, retry: false,
  });
  const sessionRows = sessions.data?.pages.flatMap(page => page.items) ?? [];
  const sessionDetail = useQuery({ queryKey: ['chat-session', sessionId], enabled: !!sessionId,
    queryFn: async () => (await apiClient.get<Session>(`/api/chat/sessions/${sessionId}`)).data, retry: false });
  useEffect(() => { if (sessionDetail.data) setCompanion(sessionDetail.data.companionCode ?? 'heapy_cat'); }, [sessionDetail.data]);
""" + s[end:]
s = s.replace("{ companionCode: 'heapy_cat' }", '{ companionCode: companion }')
start = s.index('  return (\n    <KeyboardAvoidingView')
s = s[:start] + """
  const choose = async (code: CompanionCode) => {
    if (busy || changing) return;
    setChanging(true); setError('');
    try {
      if (sessionId) {
        await apiClient.patch(`/api/chat/sessions/${sessionId}`, { companionCode: code });
        await client.invalidateQueries({ queryKey: ['chat-session', sessionId] });
        await client.invalidateQueries({ queryKey: ['chat-sessions'] });
      } else if (code !== companion) createKey.current = createIdempotencyKey();
      setCompanion(code); setView('conversation');
    } catch { setError('파트너를 변경하지 못했어요. 잠시 후 다시 시도해 주세요.'); }
    finally { setChanging(false); }
  };
  const newChat = () => { if (busy) return; setSessionId(undefined); setPending(undefined); setError(''); setInput(''); createKey.current = createIdempotencyKey(); setView('partners'); };
  const remove = async () => {
    if (!deleteTarget || busy || changing) return;
    setChanging(true);
    try { await apiClient.delete(`/api/chat/sessions/${deleteTarget.sessionId}`);
      client.removeQueries({ queryKey: ['chat-messages', deleteTarget.sessionId] });
      client.removeQueries({ queryKey: ['chat-session', deleteTarget.sessionId] });
      if (sessionId === deleteTarget.sessionId) setSessionId(undefined);
      await client.invalidateQueries({ queryKey: ['chat-sessions'] }); setDeleteTarget(undefined);
    } catch { setError('상담 기록을 삭제하지 못했어요. 다시 시도해 주세요.'); }
    finally { setChanging(false); }
  };
  const partner = partners[companion];
  return <LinearGradient colors={['#FFFFFF', '#F7F3FF', '#EDE6FF']} start={{ x: 0, y: 0 }} end={{ x: 1, y: .7 }} style={s.page}>
    <KeyboardAvoidingView style={s.page} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={s.header}>
        <Pressable accessibilityLabel={view === 'history' ? '상담으로 돌아가기' : '상담 기록 열기'} disabled={busy || changing} style={s.round} onPress={() => setView(view === 'history' ? sessionId ? 'conversation' : 'partners' : 'history')}><Text style={s.heading}>{view === 'history' ? '‹' : '≡'}</Text></Pressable>
        {view === 'conversation' && <CompanionAvatar code={companion} size={36} />}
        <View style={{ flex: 1 }}><Text style={s.heading}>{view === 'history' ? '상담 기록' : view === 'partners' ? '히피 상담' : partner.name}</Text>{view !== 'partners' && <Text style={s.caption}>{view === 'history' ? '히피냥·히피멍과 나눈 이야기' : '● ' + partner.tag}</Text>}</View>
        <Pressable disabled={busy || changing} style={s.pill} onPress={view === 'history' ? newChat : () => setView('partners')}><Text style={s.accent}>{view === 'history' ? '새 상담' : view === 'conversation' ? '바꾸기  ›' : 'HEAPY'}</Text></Pressable>
      </View>
      {!!error && <View style={s.errorBox}><Text style={s.notice}>{error}</Text>{pending && <Pressable disabled={busy} onPress={() => send(pending)}><Text style={s.accent}>같은 질문 다시 확인하기</Text></Pressable>}</View>}
      {view === 'partners' && <ScrollView contentContainerStyle={s.partnerPage}>
        <Text style={s.eyebrow}>MY HEALTH PARTNER</Text><Text style={s.title}>오늘 누구와{ '\\n' }함께할까요?</Text><Text style={s.small}>성격은 달라도, 건강 정보는 같은 기준으로 꼼꼼하게.</Text>
        <View style={s.partnerRow}>{(Object.keys(partners) as CompanionCode[]).map(code => { const cat = code === 'heapy_cat'; const p = partners[code]; return <View key={code} style={[s.partnerCard, cat && s.catCard]}>
          <View style={s.halo}><CompanionAvatar code={code} size={85} /></View>
          <Text style={[s.tag, cat && s.catTag]}>{p.tag}</Text><Text style={[s.partnerName, cat && s.white]}>{p.name}</Text><Text style={[s.small, cat && s.white]}>{p.description}</Text><Text style={[s.features, cat && s.white]}>{p.features}</Text>
          <Pressable accessibilityLabel={`${p.name} 선택`} disabled={changing || busy} onPress={() => choose(code)} style={[s.selectButton, !cat && { backgroundColor: '#7749E2' }]}><Text style={[s.accent, !cat && s.white]}>{changing ? '연결 중…' : '이 파트너 선택'}</Text></Pressable>
        </View>; })}</View><View style={s.partnerNotice}><Text style={s.caption}>● 상담 중에도 언제든 파트너를 바꿀 수 있어요</Text></View>
      </ScrollView>}
      {view === 'history' && <ScrollView contentContainerStyle={s.history} keyboardShouldPersistTaps="handled">
        <TextInput accessibilityLabel="상담 제목 검색" value={search} onChangeText={setSearch} placeholder="대화 제목 검색" style={s.search} />
        <View style={s.filters}>{(['all', 'heapy_cat', 'heapy_dog'] as const).map(code => <Pressable key={code} style={[s.filter, filter === code && s.activeFilter]} onPress={() => setFilter(code)}><Text style={[s.caption, filter === code && s.white]}>{code === 'all' ? '전체' : partners[code].name}</Text></Pressable>)}</View>
        {sessions.isPending && <ActivityIndicator />}
        {sessions.isError && <Pressable onPress={() => sessions.refetch()}><Text style={s.notice}>기록을 불러오지 못했어요. 다시 불러오기</Text></Pressable>}
        {sessionRows.filter(item => (filter === 'all' || item.companionCode === filter) && item.title.toLocaleLowerCase().includes(search.toLocaleLowerCase())).map(item => <View key={item.sessionId} style={s.historyCard}><Pressable disabled={busy} style={s.historyContent} onPress={() => { setSessionId(item.sessionId); setCompanion(item.companionCode ?? 'heapy_cat'); setPending(undefined); setError(''); setInput(''); setView('conversation'); }}><CompanionAvatar code={item.companionCode ?? 'heapy_cat'} size={40} /><View style={{ flex: 1, gap: 8 }}><Text style={s.accent}>{partners[item.companionCode ?? 'heapy_cat'].name}</Text><Text style={s.rowTitle}>{item.title}</Text><Text style={s.caption}>{new Date(item.createdAt).toLocaleDateString('ko-KR')}</Text></View></Pressable><Pressable accessibilityLabel={`${item.title} 삭제`} disabled={busy || changing} onPress={() => setDeleteTarget(item)} style={s.delete}><Text style={s.caption}>삭제</Text></Pressable></View>)}
        {!sessions.isPending && !sessions.isError && !sessionRows.length && <Text style={s.small}>아직 상담 기록이 없어요. 새 상담을 시작해 보세요.</Text>}
        {sessions.hasNextPage && <Pressable disabled={sessions.isFetchingNextPage} onPress={() => sessions.fetchNextPage()} style={s.pill}><Text style={s.accent}>이전 상담 더 보기</Text></Pressable>}
        {!!search && <Text style={s.caption}>현재 불러온 상담 제목에서 검색합니다.</Text>}
      </ScrollView>}
      {view === 'conversation' && <>
        <ScrollView ref={messageScroll} contentContainerStyle={s.messages} keyboardShouldPersistTaps="handled">
          {messages.hasNextPage && <Pressable disabled={messages.isFetchingNextPage} onPress={() => messages.fetchNextPage()}><Text style={s.accent}>이전 대화 더 보기</Text></Pressable>}
          {!rows.length && !messages.isFetching && <View style={s.assistantRow}><CompanionAvatar code={companion} size={32} /><View style={s.assistantBubble}><Text style={s.accent}>{partner.name}</Text><Text style={s.body}>{companion === 'heapy_cat' ? '안녕하세요. 필요한 내용을 함께 살펴볼게요.\\n건강에 대해 무엇이든 물어보세요.' : '안녕하세요. 건강 기록과 근거를 차근차근 살펴보겠습니다. 어떤 점이 궁금하신가요?'}</Text></View></View>}
          {messages.isFetching && <ActivityIndicator accessibilityLabel="대화 불러오는 중" />}
          {messages.isError && <Pressable onPress={() => messages.refetch()}><Text style={s.notice}>대화를 불러오지 못했어요. 다시 불러오기</Text></Pressable>}
          {rows.map(item => <View key={item.messageId} style={item.role === 'user' ? s.userRow : s.assistantRow}>
            {item.role === 'assistant' && <CompanionAvatar code={item.companionCodeSnapshot ?? companion} size={32} />}
            <View style={item.role === 'user' ? s.userBubble : s.assistantBubble}>
              <Text style={item.role === 'user' ? s.whiteCaption : s.accent}>{item.role === 'user' ? '나의 질문' : partners[item.companionCodeSnapshot ?? companion].name}</Text>
              <Text selectable style={[s.body, item.role === 'user' && s.white]}>{item.content}</Text>
              {item.responseStatus === 'partial' && <Text style={s.notice}>답변이 중간에 멈췄어요. 이어서 다시 질문해 주세요.</Text>}
              {!!item.citations.length && <Pressable accessibilityState={{ expanded: openSources === item.messageId }} onPress={() => setOpenSources(openSources === item.messageId ? undefined : item.messageId)}><Text style={s.accent}>참고한 근거 {item.citations.length}개 {openSources === item.messageId ? '접기' : '보기'}</Text></Pressable>}
              {openSources === item.messageId && item.citations.map((citation, index) => <Pressable key={index} disabled={!citation.sourceUrl || !/^https?:\\/\\//i.test(citation.sourceUrl)} onPress={() => citation.sourceUrl && Linking.openURL(citation.sourceUrl).catch(() => setError('출처 링크를 열지 못했어요.'))}><Text selectable style={s.source}>{citation.sourceTitle}{citation.sourceUrl ? '\\n' + citation.sourceUrl : ''}</Text></Pressable>)}
            </View>
          </View>)}
          {busy && <><View style={s.userRow}><View style={s.userBubble}><Text style={[s.body, s.white]}>{pending?.message ?? input}</Text></View></View><View style={s.assistantRow}><CompanionAvatar code={companion} size={32} /><View style={s.assistantBubble}><Text style={s.accent}>꼼꼼히 보는 중</Text><ActivityIndicator color="#7749E2" /><Text style={s.small}>기록과 근거를 확인하며 답변을 준비하고 있어요.</Text></View></View></>}
          {!rows.length && !busy && <View style={s.filters}>{['오늘 검진 요약 보기', '내 생활 데이터 같이 보기'].map(text => <Pressable key={text} style={s.pill} onPress={() => setInput(text)}><Text style={s.caption}>{text}</Text></Pressable>)}</View>}
        </ScrollView>
        <Text style={s.safety}>● 의학적 진단을 대신하지 않으며, 필요 시 진료를 권해요</Text>
        <View style={s.composer}><TextInput accessibilityLabel="건강 질문" placeholder="건강에 대해 무엇이든 물어보세요" placeholderTextColor="#809592" value={input} onChangeText={value => { setInput(value); if (!busy) setPending(undefined); }} editable={!busy && !changing} multiline maxLength={2000} style={s.input} /><Pressable accessibilityRole="button" accessibilityLabel="질문 보내기" disabled={busy || !input.trim() || changing} onPress={() => send()} style={[s.send, (busy || !input.trim()) && { opacity: .4 }]}><Text style={s.white}>↑</Text></Pressable></View>
      </>}
      {deleteTarget && <View style={s.confirm}><Text style={s.heading}>이 상담을 삭제할까요?</Text><Text style={s.small}>대화와 요약, 출처가 함께 삭제됩니다.</Text><View style={s.filters}><Pressable disabled={changing} onPress={() => setDeleteTarget(undefined)} style={s.pill}><Text style={s.small}>취소</Text></Pressable><Pressable disabled={changing} onPress={remove} style={s.pill}><Text style={s.notice}>삭제하기</Text></Pressable></View></View>}
    </KeyboardAvoidingView>
  </LinearGradient>;
}
const s = StyleSheet.create({
  page: { flex: 1 }, header: { padding: 24, paddingBottom: 18, flexDirection: 'row', gap: 10, alignItems: 'center', borderBottomWidth: 1, borderColor: '#E8DFFF' }, heading: { fontSize: 18, fontWeight: '700', color: '#17342D' }, caption: { fontSize: 10, lineHeight: 16, color: '#71847D' }, small: { fontSize: 12, lineHeight: 18, color: '#71847D' }, rowTitle: { fontSize: 13, lineHeight: 20, fontWeight: '700', color: '#17342D' }, round: { width: 36, height: 40, alignItems: 'center', justifyContent: 'center', borderRadius: 20, backgroundColor: '#FFFFFFBB' }, pill: { borderWidth: 1, borderColor: '#E5DCFF', borderRadius: 22, minHeight: 36, paddingHorizontal: 18, justifyContent: 'center', backgroundColor: '#FFFFFFAA' }, accent: { color: '#7542EA', fontSize: 11, fontWeight: '600', lineHeight: 17 }, eyebrow: { fontSize: 10, color: '#7542EA', fontWeight: '700' }, title: { fontSize: 27, fontWeight: '800', lineHeight: 32, color: '#122D25' }, partnerPage: { padding: 24, gap: 10, paddingTop: 24 }, partnerRow: { flexDirection: 'row', gap: 16, marginTop: 18 }, partnerCard: { flex: 1, minHeight: 366, padding: 13, paddingTop: 18, borderWidth: 1, borderColor: '#C9CCF5', backgroundColor: '#FBFCFF', borderRadius: 26, gap: 14, boxShadow: '0px 14px 28px rgba(41,31,89,.09)' }, catCard: { backgroundColor: '#7749E2', borderColor: '#9E7AF2' }, halo: { padding: 7, backgroundColor: '#DDE2FF', borderRadius: 52, alignSelf: 'center', marginBottom: 2 }, tag: { borderRadius: 12, backgroundColor: '#E8E5FF', color: '#6C3CE0', textAlign: 'center', fontSize: 10, paddingVertical: 6 }, catTag: { backgroundColor: '#A887F5', color: 'white' }, partnerName: { fontSize: 22, fontWeight: '800', color: '#122D25' }, features: { fontSize: 11, lineHeight: 24, color: '#165A46', marginTop: 6 }, selectButton: { minHeight: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', backgroundColor: 'white', marginTop: 'auto' }, white: { color: 'white' }, whiteCaption: { color: '#E5D7FF', fontSize: 10 }, partnerNotice: { alignSelf: 'center', padding: 12, borderWidth: 1, borderColor: '#E5DCFF', borderRadius: 24, backgroundColor: '#FFFFFFAA', marginTop: 14 }, history: { padding: 24, gap: 14 }, search: { padding: 14, minHeight: 44, borderWidth: 1, borderColor: '#E5DCFF', borderRadius: 24, color: '#17342D', backgroundColor: '#FFFFFFCC', fontSize: 12 }, filters: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' }, filter: { borderWidth: 1, borderColor: '#E5DCFF', backgroundColor: '#FFFFFFBB', borderRadius: 20, paddingVertical: 10, paddingHorizontal: 24 }, activeFilter: { backgroundColor: '#7749E2' }, historyCard: { borderWidth: 1, borderColor: '#E5DCFF', borderRadius: 24, backgroundColor: '#FFFFFFDD', padding: 16, gap: 8, boxShadow: '0px 8px 20px rgba(41,31,89,.04)' }, historyContent: { flexDirection: 'row', gap: 12, alignItems: 'center' }, delete: { alignSelf: 'flex-end', minHeight: 32, paddingHorizontal: 10, justifyContent: 'center' }, messages: { padding: 20, gap: 20, flexGrow: 1 }, assistantRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 }, userRow: { alignItems: 'flex-end' }, assistantBubble: { flexShrink: 1, padding: 16, borderWidth: 1, borderColor: '#E5DCFF', borderRadius: 22, backgroundColor: '#FFFFFFEE', gap: 10 }, userBubble: { maxWidth: '85%', padding: 16, borderRadius: 22, backgroundColor: '#8451E9', gap: 10 }, body: { fontSize: 13, lineHeight: 22, color: '#17342D' }, notice: { color: '#9A603C', fontSize: 12, lineHeight: 18 }, errorBox: { padding: 16, backgroundColor: '#FFF4E9', gap: 8 }, source: { color: '#637A73', fontSize: 11, lineHeight: 18 }, safety: { paddingHorizontal: 24, fontSize: 9, lineHeight: 15, color: '#71847D', marginBottom: 8 }, composer: { marginHorizontal: 16, marginBottom: 14, padding: 7, flexDirection: 'row', alignItems: 'flex-end', borderRadius: 30, borderWidth: 1, borderColor: '#E5DCFF', backgroundColor: '#FFFFFFDD', boxShadow: '0px 6px 18px rgba(41,31,89,.07)' }, input: { flex: 1, minHeight: 36, maxHeight: 100, paddingHorizontal: 12, paddingVertical: 8, color: '#17342D', fontSize: 12 }, send: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center', borderRadius: 18, backgroundColor: '#7749E2' }, confirm: { position: 'absolute', bottom: 0, left: 0, right: 0, borderRadius: 24, padding: 24, gap: 20, backgroundColor: 'white', boxShadow: '0px -10px 50px rgba(0,0,0,.2)' },
});
"""
s = s.replace('  Alert,\n', '')
p.write_text(s, encoding='utf-8')
# 다른 탭으로 이동해도 예시 홈 편집 상태를 유지한다.
p = root / 'src/features/home/HomeScreen.tsx'
s = p.read_text(encoding='utf-8')
s = s.replace("        {selected === 'home' ? (\n          <HomeDashboard", "        <View style={{ flex: 1, display: selected === 'home' ? 'flex' : 'none' }}>\n          <HomeDashboard")
s = s.replace("          />\n        ) : selected === 'chatbot' ? (", "          />\n        </View>\n        {selected === 'home' ? null : selected === 'chatbot' ? (")
p.write_text(s, encoding='utf-8')
p = root / 'src/features/home/HomeDashboard.tsx'
s = p.read_text(encoding='utf-8').replace('今日', '브리핑')
p.write_text(s, encoding='utf-8')
