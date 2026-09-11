# 작성자: 김진우 — 카드 설정과 홈에서 숨기기 동작을 명확히 분리한다.
from pathlib import Path
p = Path('C:/Users/jinwo/heapy-frontend/src/features/home/HomeDashboard.tsx')
s = p.read_text(encoding='utf-8')
s = s.replace("configurable ? '설정' : '숨기기'", "configurable ? '설정' : '선택 해제'")
old = '''      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${moduleLabels[id]} 숨기기`}
        onPress={onRemove}
        style={{ flex: 1 }}
      >
        <Text style={s.rowTitle}>{moduleLabels[id]}</Text>
        <Text style={s.caption}>{descriptions[id]}</Text>
      </Pressable>'''
new = '''      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={s.rowTitle} numberOfLines={1}>{moduleLabels[id]}</Text>
        <Text style={s.caption} numberOfLines={1}>{descriptions[id]}</Text>
      </View>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${moduleLabels[id]} 숨기기`}
        accessibilityHint="홈에서 숨기고 추가할 수 있는 항목으로 이동합니다"
        onPress={onRemove}
        style={s.removeButton}
      >
        <Text style={s.removeLabel}>숨기기</Text>
      </Pressable>'''
assert old in s
s = s.replace(old, new)
s = s.replace('손잡이를 드래그해 순서를 바꾸고, 항목 이름을 눌러 숨겨요.', '톱니바퀴로 설정하고, 숨기기로 빼고, 손잡이로 순서를 바꿔요.')
start=s.index('  selectedRow: {');end=s.index('  circle:',start)
block=s[start:end].replace('gap: 20', 'gap: 10')
s=s[:start]+block+s[end:]
s=s.replace('  drag: {', '''  removeButton: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  removeLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: '#A66059',
    backgroundColor: '#FFF1EE',
    paddingHorizontal: 8,
    paddingVertical: 6,
    borderRadius: 10,
  },
  drag: {''')
p.write_text(s,encoding='utf-8')
