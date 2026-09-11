from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend')
source=p/'src/features/health/healthModel.test.ts'
target=p/'__tests__/healthModel.test.ts'
s=source.read_text(encoding='utf-8').replace("from './healthModel'", "from '../src/features/health/healthModel'").replace("from './types'", "from '../src/features/health/types'")
target.write_text(s,encoding='utf-8')
source.unlink()
