// 작성자: 김진우 — Figma 원본 이미지와 원본 크롭 비율을 사용한다.
import React from 'react';
import { Image, View } from 'react-native';
export type CompanionCode = 'heapy_cat' | 'heapy_dog';
export function CompanionAvatar({ code = 'heapy_cat', size = 82 }: { code?: CompanionCode; size?: number }) {
  return <View style={{ width: size, height: size, borderRadius: size / 2, overflow: 'hidden', backgroundColor: 'white' }}>
    {code === 'heapy_cat' ? <Image source={require('../../assets/home-chat/cat.png')} style={{ position: 'absolute', width: size * 3.125, height: size * 2.8542, left: -size * .5417, top: -size * .1667 }} /> : <Image source={require('../../assets/home-chat/dog.png')} style={{ width: size, height: size }} />}
  </View>;
}
