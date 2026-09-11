from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend/src/features/health')
(p/'useHealthMotion.ts').write_text('''// 작성자: 김진우 — 카드가 보일 때 한 번 재생하고 동작 줄이기 설정에서는 즉시 완성 상태를 표시한다.
import {createContext,RefObject,useContext,useEffect,useRef} from 'react';
import {Animated,Easing,useWindowDimensions,View} from 'react-native';
import {useReducedMotion} from '../../shared/hooks/useReducedMotion';
export const HealthMotionContext=createContext(new Set<()=>void>());
export function useHealthMotion(key:string,host:RefObject<View|null>) {
 const reduced=useReducedMotion();
 const listeners=useContext(HealthMotionContext);
 const {height:windowHeight}=useWindowDimensions();
 const progress=useRef(new Animated.Value(1)).current;
 useEffect(()=>{
   progress.stopAnimation();
   if(reduced){progress.setValue(1);return;}
   progress.setValue(0);
   let started=false,disposed=false;
   const animation=Animated.timing(progress,{toValue:1,duration:620,easing:Easing.out(Easing.cubic),useNativeDriver:false,isInteraction:false});
   const reveal=()=>{
     if(started||disposed)return;
     host.current?.measureInWindow((_x,y,width,height)=>{
       if(!disposed&&!started&&width>0&&height>0&&y<windowHeight-100&&y+height>80){
         started=true;listeners.delete(reveal);animation.start();
       }
     });
   };
   listeners.add(reveal);
   const frame=requestAnimationFrame(reveal);
   return()=>{disposed=true;listeners.delete(reveal);cancelAnimationFrame(frame);animation.stop();};
 },[key,reduced,progress,host,listeners,windowHeight]);
 return progress;
}
''',encoding='utf-8')
f=p/'HealthChart.tsx';s=f.read_text(encoding='utf-8');s=s.replace('  const progress = useHealthMotion(', '  const host = useRef<View>(null);\n  const progress = useHealthMotion(',1)
start=s.index('  const progress = useHealthMotion(');end=s.index('\n  useEffect',start)
block=s[start:end];i=block.rfind('  );');assert i>=0
block=block[:i]+'    host,\n'+block[i:]
s=s[:start]+block+s[end:]
s=s.replace("testID={'health-chart-' + title}","ref={host}\n      testID={'health-chart-' + title}",1)
f.write_text(s,encoding='utf-8')
f=p/'HealthScreen.tsx';s=f.read_text(encoding='utf-8').replace('useEffect, useState','useEffect, useRef, useState').replace("import { useHealthMotion }", "import { HealthMotionContext, useHealthMotion }")
s=s.replace('  const motion = useHealthMotion(label + field);', '  const host = useRef<View>(null);\n  const motion = useHealthMotion(label + field, host);')
s=s.replace("testID={'health-metric-' + field}","ref={host}\n      testID={'health-metric-' + field}",1)
start=s.index('export function HealthScreen(')
pos=s.index('  const client = useQueryClient();',start)
s=s[:pos]+"  const motionListeners = useRef(new Set<()=>void>());\n"+s[pos:]
start=s.index('    <ScrollView',start)
s=s[:start]+s[start:].replace('    <ScrollView','    <HealthMotionContext.Provider value={motionListeners.current}>\n    <ScrollView\n      onScroll={() => motionListeners.current.forEach(reveal=>reveal())}\n      scrollEventThrottle={80}',1)
end=s.index('    </ScrollView>',start);s=s[:end]+s[end:].replace('    </ScrollView>','    </ScrollView>\n    </HealthMotionContext.Provider>',1)
f.write_text(s,encoding='utf-8')
