import React,{useState} from 'react';
import {Pressable,Text,View} from 'react-native';
import {useQuery} from '@tanstack/react-query';
import {dataConnectionApi} from '../dataConnection/dataConnectionApi';
import {CheckupDetail} from '../dataConnection/types';
import {CheckupOpinionSections} from '../dataConnection/CheckupOpinionSections';
import {findingCards} from '../dataConnection/CheckupFindings';
import {hs} from './healthStyles';
import {format} from './healthModel';

// 작성자: 김진우 — 결과값 증감을 임의의 개선·악화로 판정하지 않고 기관 상태를 보존한다.
export function HealthCheckups({mode,onMode,onRegister,analysis}:{mode:'overview'|'compare'|'all';onMode:(mode:'overview'|'compare'|'all')=>void;onRegister:()=>void;analysis:React.ReactNode}){
 const records=useQuery({queryKey:['health','checkups'],queryFn:({signal})=>dataConnectionApi.getCheckups(signal),retry:false});
 const [currentId,setCurrent]=useState(''),[previousId,setPrevious]=useState(''),[filter,setFilter]=useState('전체');
 const current=currentId||records.data?.[0]?.recordId,previous=previousId||records.data?.[1]?.recordId;
 const detail=useQuery({queryKey:['health','checkup',current],queryFn:({signal})=>dataConnectionApi.getCheckup(current!,signal),enabled:!!current,retry:false});
 const before=useQuery({queryKey:['health','checkup',previous],queryFn:({signal})=>dataConnectionApi.getCheckup(previous!,signal),enabled:mode==='compare'&&!!previous,retry:false});
 const all=detail.data?.results??[];const statuses=[...new Set(all.map(r=>r.status||'판정 미제공'))];
 function selector(label:string,value:string|undefined,set:(value:string)=>void){return <View style={hs.card}><Text style={hs.muted}>{label}</Text><View style={[hs.row,{flexWrap:'wrap'}]}>{records.data?.map(r=><Pressable key={r.recordId} accessibilityRole="radio" accessibilityState={{checked:r.recordId===value}} onPress={()=>{set(r.recordId);setFilter('전체');}} style={[hs.pill,r.recordId===value&&hs.active]}><Text style={[hs.pillText,r.recordId===value&&hs.white]}>{r.measuredAt||'날짜 미제공'}</Text></Pressable>)}</View></View>;}
 if(records.isPending||detail.isPending&&!!current)return <Text style={hs.muted}>검진 기록을 불러오고 있어요.</Text>;
 if(records.isError||detail.isError)return <View style={hs.card}><Text style={hs.error}>검진 기록을 불러오지 못했어요.</Text><Pressable onPress={()=>{records.refetch();detail.refetch();}}><Text style={hs.pillText}>다시 불러오기</Text></Pressable></View>;
 if(!records.data?.length)return <View style={hs.card}><Text style={hs.section}>아직 검진 기록이 없어요.</Text><Pressable onPress={onRegister} style={hs.pill}><Text style={hs.pillText}>검진 결과 등록하기</Text></Pressable></View>;
 return <>
 {mode!=='overview'&&selector(mode==='compare'?'현재 회차':'검진 회차',current,setCurrent)}
 {mode==='compare'&&selector('비교할 이전 회차',previous,setPrevious)}
 {mode==='overview'&&<View style={hs.card}><View style={hs.between}><Text style={hs.section}>최근 검진 핵심 수치</Text><Text style={hs.muted}>{detail.data?.measuredAt}</Text></View><Text style={hs.muted}>{detail.data?.providerName||'기관 미제공'}</Text><View style={[hs.row,{flexWrap:'wrap'}]}>{all.slice(0,4).map((r,i)=><View key={r.itemCode} style={[hs.metric,{backgroundColor:['#FFF0E2','#E1F8F0','#F1EAFF','#E8F2FF'][i],minWidth:'44%'}]}><Text style={hs.muted}>{r.itemName}</Text><Text style={hs.text}>{r.value} {r.unit}</Text><Text style={hs.pillText}>{r.status||'판정 미제공'}</Text></View>)}</View></View>}
 {analysis}
 {mode!=='compare'&&<><View style={[hs.row,{flexWrap:'wrap'}]}>{statuses.map(status=><View key={status} style={hs.metric}><Text style={hs.muted}>{status}</Text><Text style={hs.value}>{all.filter(r=>(r.status||'판정 미제공')===status).length}개</Text></View>)}</View>{mode==='all'&&<><View style={[hs.row,{flexWrap:'wrap'}]}>{['전체',...statuses].map(status=><Pressable key={status} accessibilityRole="radio" accessibilityState={{checked:filter===status}} onPress={()=>setFilter(status)} style={[hs.pill,filter===status&&hs.active]}><Text style={[hs.pillText,filter===status&&hs.white]}>{status}</Text></Pressable>)}</View><View style={hs.card}><Text style={hs.section}>결과 전체 보기 · {all.length}개</Text>{all.filter(r=>filter==='전체'||(r.status||'판정 미제공')===filter).map(r=><View key={r.itemCode} style={[hs.between,{paddingVertical:10,borderBottomWidth:1,borderBottomColor:'#E7EFEB'}]}><View style={hs.spacer}><Text style={hs.text}>{r.itemName}</Text><Text style={hs.muted}>{r.status||'판정 미제공'}</Text></View><Text style={hs.text}>{r.value} {r.unit}</Text></View>)}</View><CheckupOpinionSections examinations={findingCards(detail.data?.findings??[]).map(r=>({...r,editable:false}))} overall={findingCards(detail.data?.overallOpinions??[]).map(r=>({...r,editable:false}))}/></>}
 {mode==='overview'&&<View style={hs.row}><Pressable onPress={()=>onMode('compare')} style={[hs.pill,hs.spacer]}><Text style={hs.pillText}>과거 검진과 비교</Text></Pressable><Pressable onPress={()=>onMode('all')} style={[hs.pill,hs.active,hs.spacer]}><Text style={[hs.pillText,hs.white]}>결과 전체 보기</Text></Pressable></View>}</>}
 {mode==='compare'&&(previous===current?<Text style={hs.error}>서로 다른 회차를 선택해 주세요.</Text>:!previous?<Text style={hs.muted}>비교하려면 검진 기록이 2회 이상 필요해요.</Text>:before.isPending?<Text style={hs.muted}>이전 회차를 불러오고 있어요.</Text>:before.isError?<Text style={hs.error}>이전 회차를 불러오지 못했어요.</Text>:<Compare before={before.data} current={detail.data}/>)}
 </>;
}
function Compare({before,current}:{before?:CheckupDetail;current?:CheckupDetail}){
 const codes=[...new Set([...(before?.results??[]),...(current?.results??[])].map(r=>r.itemCode))];
 return <View style={hs.card}><Text style={hs.section}>주요 항목 변화</Text><Text style={hs.muted}>과거 수치 → 현재 수치 · 기관 판정</Text>{codes.map(code=>{const a=before?.results.find(r=>r.itemCode===code),b=current?.results.find(r=>r.itemCode===code);const numeric=a?.numericValue!=null&&b?.numericValue!=null;const same=a&&b&&!!a.unit&&a.unit===b.unit;const change=same&&numeric?Number(b.numericValue)-Number(a.numericValue):null;return <View key={code} style={{gap:8,paddingVertical:14,borderBottomWidth:1,borderBottomColor:'#E7EFEB'}}><Text style={hs.section}>{b?.itemName||a?.itemName}</Text><View style={hs.between}><Text style={hs.text}>{a?.value??'미기록'} {a?.unit}</Text><Text style={hs.muted}>→</Text><Text style={hs.text}>{b?.value??'미기록'} {b?.unit}</Text></View><Text style={hs.muted}>{a?.status||'판정 미제공'} → {b?.status||'판정 미제공'}{change!==null?` · 차이 ${change>0?'+':''}${format(change)} ${b?.unit}`:a&&b&&!same?' · 단위가 달라 수치 비교 불가':''}</Text></View>;})}</View>;
}
