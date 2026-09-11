from pathlib import Path
import subprocess

root=Path('C:/Users/jinwo/heapy-backend').resolve()
work=root/'.worktrees/samsung-sync'
base='973a28d682d89f1fcbbba7b2670d7ada30f03fc7'
git=['git','-c',f'safe.directory={work.as_posix()}','-C',str(work)]
def normalize(data): return data.replace(b'\r\n',b'\n')
paths=subprocess.check_output(git+['diff','--name-only','-z','--diff-filter=ACM',base,'HEAD']).decode().split('\0')
changes=[]
for name in filter(None,paths):
    target=(root/name).resolve()
    assert target.is_relative_to(root) and not target.is_relative_to(root/'.git')
    desired=(work/name).read_bytes()
    old=subprocess.run(git+['show',f'{base}:{name}'],capture_output=True)
    if target.exists():
        current=target.read_bytes()
        if normalize(current)==normalize(desired): continue
        if old.returncode or normalize(current)!=normalize(old.stdout):
            checkout=subprocess.run(['git','-c',f'safe.directory={root.as_posix()}','-C',str(root),'show',f'HEAD:{name}'],capture_output=True)
            if checkout.returncode or normalize(current)!=normalize(checkout.stdout):
                raise RuntimeError(f'기존 변경과 충돌하여 자동 덮어쓰지 않음: {name}')
    elif old.returncode==0:
        raise RuntimeError(f'현재 작업에서 삭제된 파일은 복원하지 않음: {name}')
    changes.append((target,desired))
for target,desired in changes:
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(desired)
print(f'기존 변경을 보존하며 동기화 변경 {len(changes)}개 파일 반영')
