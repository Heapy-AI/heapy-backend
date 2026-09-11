// 작성자: 김진우 — 전환 시 한 번 재생하고 동작 줄이기 설정에서는 즉시 완성 상태를 표시한다.
import {useEffect, useRef} from 'react';
import {Animated, Easing} from 'react-native';
import {useReducedMotion} from '../../shared/hooks/useReducedMotion';
export function useHealthMotion(key: string) {
  const reduced = useReducedMotion();
  const progress = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    progress.stopAnimation();
    if (reduced) { progress.setValue(1); return; }
    progress.setValue(0);
    const animation = Animated.timing(progress, {toValue: 1, duration: 620, easing: Easing.out(Easing.cubic), useNativeDriver: false, isInteraction: false});
    animation.start();
    return () => animation.stop();
  }, [key, reduced, progress]);
  return progress;
}
