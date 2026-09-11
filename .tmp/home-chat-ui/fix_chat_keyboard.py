# 작성자: 김진우 — 채팅 영역과 하단 탭을 하나의 키보드 회피 영역으로 처리한다.
from pathlib import Path
r=Path('C:/Users/jinwo/heapy-frontend')
p=r/'src/features/home/HomeScreen.tsx';s=p.read_text(encoding='utf-8')
s=s.replace('  Image,\n', '  Image,\n  Keyboard,\n  KeyboardAvoidingView,\n')
s=s.replace("import { NativeStackScreenProps }", "import { useSafeAreaInsets } from 'react-native-safe-area-context';\nimport { NativeStackScreenProps }")
s=s.replace('  const [homeEditing,', '''  const insets = useSafeAreaInsets();
  const [keyboardVisible, setKeyboardVisible] = useState(false);
  // 작성자: 김진우 — 키보드가 닫히면 탭 표시를 복원하고 이벤트를 정리한다.
  useEffect(() => {
    if (Platform.OS === 'web') return;
    const show = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow', event => {
      Keyboard.scheduleLayoutAnimation(event);
      setKeyboardVisible(true);
    });
    const hide = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide', event => {
      Keyboard.scheduleLayoutAnimation(event);
      setKeyboardVisible(false);
    });
    return () => { show.remove(); hide.remove(); };
  }, []);
  const [homeEditing,''')
s=s.replace('    <ScreenBackground>\n', '''    <ScreenBackground>
      <KeyboardAvoidingView
        style={styles.content}
        enabled={selected === 'chatbot' && Platform.OS !== 'web'}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        keyboardVerticalOffset={insets.top}
      >
''')
s=s.replace("          selected === 'home' && homeEditing && { display: 'none' },", "          ((selected === 'home' && homeEditing) || (selected === 'chatbot' && keyboardVisible)) && { display: 'none' },")
s=s.replace('    </ScreenBackground>', '      </KeyboardAvoidingView>\n    </ScreenBackground>')
p.write_text(s,encoding='utf-8')
p=r/'src/features/chat/ChatScreen.tsx';s=p.read_text(encoding='utf-8')
s=s.replace('  KeyboardAvoidingView,','  Keyboard,')
old='''      <KeyboardAvoidingView
        style={s.page}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >'''
assert old in s
s=s.replace(old,'      <View style={s.page}>').replace('      </KeyboardAvoidingView>', '      </View>')
s=s.replace('''              ref={messageScroll}
''', '''              ref={messageScroll}
              keyboardDismissMode={Platform.OS === 'ios' ? 'interactive' : 'on-drag'}
              onLayout={() => {
                if (Keyboard.isVisible()) {
                  requestAnimationFrame(() => messageScroll.current?.scrollToEnd({ animated: true }));
                }
              }}
''')
p.write_text(s,encoding='utf-8')
