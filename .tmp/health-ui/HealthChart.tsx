import React, {useMemo,useRef,useState} from 'react';
import {PanResponder,Pressable,ScrollView,Text,View} from 'react-native';
import Svg,{Circle,Line,Path,Rect,Text as SvgText} from 'react-native-svg';
import {Series} from './types';
import {format} from './healthModel';
import {hs} from './healthStyles';
const palette=['#20BA8A','#4285F4','#8057E0','#FF8844','#E75478','#007F78'];
// 작성자: 김진우 — 그래프는 실제 좌표로 그리며 단위별로 분리한다. 확대·이동·값 선택과 수치표를 제공한다.
export function HealthChart({title,series,kind='line',note}:{title:string;series:Series[];kind?:'line'|'bar'|'stack';note?:string}) {
 const [width,setWidth]=useState(300),[zoom,setZoom]=useState(1),[table,setTable]=useState(false),[selected,setSelected]=useState('');
 const gesture=useRef({distance:1,zoom:1});const zoomRef=useRef(zoom);zoomRef.current=zoom;
 const responder=useMemo(()=>PanResponder.create({onMoveShouldSetPanResponder:e=>e.nativeEvent.touches.length===2,onPanResponderGrant:e=>{const [a,b]=e.nativeEvent.touches;if(a&&b)gesture.current={distance:Math.hypot(a.pageX-b.pageX,a.pageY-b.pageY),zoom:zoomRef.current};},onPanResponderMove:e=>{const [a,b]=e.nativeEvent.touches;if(a&&b)setZoom(Math.min(4,Math.max(1,gesture.current.zoom*Math.hypot(a.pageX-b.pageX,a.pageY-b.pageY)/Math.max(1,gesture.current.distance))));}}),[]);
 const dates=[...new Set(series.flatMap(s=>s.points.map(p=>p.date)))].sort();
 const chartWidth=Math.max(width,width*zoom), left=40,right=12,top=18,bottom=176;
 const max=Math.max(1,...dates.map(date=>kind==='stack'?series.reduce((sum,s)=>sum+(s.points.find(p=>p.date===date)?.value??0),0):Math.max(0,...series.map(s=>s.points.find(p=>p.date===date)?.value??0))))*1.12;
 const x=(i:number)=>left+(i+.5)*(chartWidth-left-right)/Math.max(1,dates.length),y=(v:number)=>bottom-v/max*(bottom-top);
 const date=dates.includes(selected)?selected:dates[dates.length-1];
 return <View style={hs.card} onLayout={e=>setWidth(Math.max(180,e.nativeEvent.layout.width-32))}>
  <Text accessibilityRole="header" style={hs.section}>{title}</Text>
  <View style={[hs.row,{flexWrap:'wrap'}]}>{series.map((s,i)=><Text key={s.key} style={[hs.muted,{color:palette[i%palette.length]}]}>● {s.label} ({s.unit})</Text>)}</View>
  {!dates.length?<Text style={[hs.muted,{paddingVertical:40,textAlign:'center'}]}>표시할 기록이 없어요.</Text>:<>
   <View {...responder.panHandlers}><ScrollView horizontal showsHorizontalScrollIndicator><Svg width={chartWidth} height={210}>
    {[0,.5,1].map(r=><React.Fragment key={r}><Line x1={left} x2={chartWidth-right} y1={y(max*r)} y2={y(max*r)} stroke="#E6EFEC"/><SvgText x={left-6} y={y(max*r)+4} textAnchor="end" fontSize={9} fill="#6D807A">{format(max*r,0)}</SvgText></React.Fragment>)}
    {series.map((s,si)=>kind==='line'?<React.Fragment key={s.key}>
      <Path d={dates.map((d,i)=>{const p=s.points.find(p=>p.date===d);if(!p)return '';const prior=i>0?s.points.find(p=>p.date===dates[i-1]):null;return `${prior?'L':'M'} ${x(i)} ${y(p.value)}`;}).join(' ')} fill="none" stroke={palette[si%palette.length]} strokeWidth={2.5}/>
      {s.points.map(p=><Circle key={p.date} cx={x(dates.indexOf(p.date))} cy={y(p.value)} r={date===p.date?5:3} fill="white" stroke={palette[si%palette.length]} strokeWidth={2}/>)}</React.Fragment>:
      s.points.map(p=>{const i=dates.indexOf(p.date),base=kind==='stack'?series.slice(0,si).reduce((sum,a)=>sum+(a.points.find(v=>v.date===p.date)?.value??0),0):0;const bar=(chartWidth-left-right)/Math.max(1,dates.length)*.62;return <Rect key={s.key+p.date} x={x(i)-bar/2} y={y(base+p.value)} width={bar} height={Math.max(0,y(base)-y(base+p.value))} fill={palette[si%palette.length]} rx={kind==='stack'?0:4}/>;}))}
    {dates.map((d,i)=><React.Fragment key={d}><Rect x={x(i)-(chartWidth-left-right)/dates.length/2} y={0} width={(chartWidth-left-right)/dates.length} height={190} fill="transparent" onPress={()=>setSelected(d)}/>{(dates.length<9||i%Math.ceil(dates.length/6)===0||i===dates.length-1)&&<SvgText x={x(i)} y={199} textAnchor="middle" fontSize={9} fill="#6D807A">{d.slice(5).replace('-','/')}</SvgText>}</React.Fragment>)}
   </Svg></ScrollView></View>
   <Text style={hs.text}>{date} · {series.map(s=>`${s.label} ${format(s.points.find(p=>p.date===date)?.value)} ${s.unit}`).join(' / ')}</Text>
   <View style={hs.between}><Text style={hs.muted}>밀어서 이동 · 두 손가락으로 확대</Text><View style={hs.row}><Pressable accessibilityRole="button" accessibilityLabel="그래프 축소" onPress={()=>setZoom(z=>Math.max(1,z-.5))}><Text style={hs.section}>−</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel="그래프 확대" onPress={()=>setZoom(z=>Math.min(4,z+.5))}><Text style={hs.section}>＋</Text></Pressable></View></View>
  </>}
  {!!note&&<Text style={hs.muted}>{note}</Text>}
  <Pressable accessibilityRole="button" onPress={()=>setTable(v=>!v)}><Text style={hs.pillText}>{table?'수치표 접기':'수치표 보기'}</Text></Pressable>
  {table&&dates.map(d=><View key={d} style={{gap:4}}><Text style={hs.text}>{d}</Text>{series.map(s=>{const p=s.points.find(p=>p.date===d);return <Text key={s.key} style={hs.muted}>{s.label}: {format(p?.value)} {s.unit}{p?` · 기록 ${p.recordedDays}일 / 구간 ${p.spanDays}일`:''}</Text>;})}</View>)}
 </View>;
}
