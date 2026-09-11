from pathlib import Path
root=Path('C:/Users/jinwo/heapy-frontend')
p=root/'src/features/dataConnection/samsungSync.ts'
p.write_text(p.read_text(encoding='utf-8').replace("const to = [shiftDay(from, 29), toDay].sort()[0];", "const candidate = shiftDay(from, 29);\n        const to = candidate < toDay ? candidate : toDay;"),encoding='utf-8')
p=root/'android/app/src/main/java/com/heapy/app/SamsungHealthReader.kt'
p.write_text(p.read_text(encoding='utf-8').replace('item.startLocalDateTime.toLocalDate()', 'item.getStartLocalDateTime().toLocalDate()'),encoding='utf-8')
