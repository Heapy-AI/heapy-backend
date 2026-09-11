import React,{useRef,useState} from 'react';
import {PanResponder,Pressable,ScrollView,Text,TextInput,View} from 'react-native';
import Svg,{Circle,Line,Text as SvgText} from 'react-native-svg';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {PrimaryButton} from '../../shared/components/PrimaryButton';
import {ConfirmModal} from '../../shared/components/ConfirmModal';
import {createIdempotencyKey} from '../../shared/utils/idempotency';
import {healthApi} from './healthApi';
import {EntryKind,HealthRecord} from './types';
import {format,koreanDay,koreanTime,numeric} from './healthModel';
import {hs} from './healthStyles';

const labels:Record<EntryKind,string>={sleep:'수면 기록',blood_pressure:'혈압 기록',body_composition:'신체 정보',water:'물 섭취 기록',blood_glucose:'혈당 기록'};
export function toInstant(day:string,time:string) {
 if(!/^\d{4}-\d{2}-\d{2}$/.test(day)||!/^([01]\d|2[0-3]):[0-5]\d$/.test(time))throw new Error('날짜와 시각을 확인해 주세요.');
 const d=new Date(`${day}T${time}:00+09:00`);
 if(!Number.isFinite(d.getTime())||koreanDay(d)!==day||d.getTime()>Date.now())throw new Error('기록 시각은 유효한 과거 시각이어야 해요.');
 return d.toISOString();
}
const clock=(value:number)=>`${String(Math.floor(value/60)).padStart(2,'0')}:${String(value%60).padStart(2,'0')}`;
// 작성자: 김진우 — 다이얼과 접근성용 시각 입력을 같은 상태로 연결한다.
function SleepDial({start,end,onStart,onEnd}:{start:number;end:number;onStart:(n:number)=>void;onEnd:(n:number)=>void}) {
 const active=useRef<'start'|'end'>('start');
 const current=useRef({start,end,onStart,onEnd});current.current={start,end,onStart,onEnd};
 const angle=(n:number)=>n/1440*Math.PI*2-Math.PI/2;
 const position=(n:number)=>({x:150+112*Math.cos(angle(n)),y:150+112*Math.sin(angle(n))});
 const change=(x:number,y:number)=>{const a=(Math.atan2(y-150,x-150)+Math.PI/2+2*Math.PI)%(2*Math.PI);const value=Math.round(a/(2*Math.PI)*1440/5)*5%1440;(active.current==='start'?current.current.onStart:current.current.onEnd)(value);};
 const pan=useRef(PanResponder.create({onStartShouldSetPanResponder:()=>true,onMoveShouldSetPanResponder:()=>true,onPanResponderGrant:e=>{const {locationX:x,locationY:y}=e.nativeEvent;const a=position(current.current.start),b=position(current.current.end);active.current=Math.hypot(x-a.x,y-a.y)<Math.hypot(x-b.x,y-b.y)?'start':'end';change(x,y);},onPanResponderMove:e=>change(e.nativeEvent.locationX,e.nativeEvent.locationY)})).current;
 const a=position(start),b=position(end),duration=(end-start+1440)%1440;
 return <View style={hs.card}><Text style={[hs.muted,{textAlign:'center'}]}>양 끝의 손잡이를 밀어 시간을 조정해요</Text><View style={{alignSelf:'center',width:300,height:300}} {...pan.panHandlers}><Svg pointerEvents="none" width={300} height={300}>
 <Circle cx={150} cy={150} r={112} fill="none" stroke="#E5EEEB" strokeWidth={21}/>
 <Circle cx={150} cy={150} r={112} fill="none" stroke="#27BFA6" strokeWidth={21} strokeDasharray={`${duration/1440*704} 704`} rotation={start/1440*360-90} origin="150,150"/>
 {Array.from({length:24},(_,i)=>{const t=i/24*2*Math.PI;return <Line key={i} x1={150+91*Math.sin(t)} y1={150-91*Math.cos(t)} x2={150+97*Math.sin(t)} y2={150-97*Math.cos(t)} stroke="#B8D5CC"/>;})}
 <Circle cx={a.x} cy={a.y} r={14} fill="#20BA8A" stroke="white" strokeWidth={4}/><Circle cx={b.x} cy={b.y} r={14} fill="#4285F4" stroke="white" strokeWidth={4}/>
 <SvgText x={150} y={133} textAnchor="middle" fontSize={27} fontWeight="700" fill="#173A31">{clock(start)}</SvgText><SvgText x={150} y={173} textAnchor="middle" fontSize={27} fontWeight="700" fill="#173A31">{clock(end)}</SvgText>
 </Svg></View><Text style={[hs.value,{textAlign:'center'}]}>{Math.floor(duration/60)}시간 {duration%60}분</Text></View>;
}
function Field({label,value,onChange,unit}:{label:string;value:string;onChange:(s:string)=>void;unit?:string}){return <View style={hs.field}><Text style={hs.muted}>{label}</Text><View style={hs.row}><TextInput accessibilityLabel={label} keyboardType="decimal-pad" value={value} onChangeText={onChange} style={[hs.input,{flex:1,fontSize:24,textAlign:'right'}]}/>{!!unit&&<Text style={hs.muted}>{unit}</Text>}</View></View>;}

export function HealthEntry({kind,onBack}:{kind:EntryKind;onBack:()=>void}) {
 const client=useQueryClient();const [day,setDay]=useState(koreanDay()),[time,setTime]=useState(koreanTime(new Date().toISOString()));
 const [values,setValues]=useState<Record<string,string>>({}),[fasting,setFasting]=useState<boolean|null>(null),[start,setStart]=useState(23*60),[end,setEnd]=useState(7*60);
 const [busy,setBusy]=useState(false),[error,setError]=useState('');const pending=useRef<{body:string;key:string}|null>(null);
 const set=(key:string)=>(value:string)=>setValues(v=>({...v,[key]:value}));
 const number=(key:string,required=true)=>{if(!values[key]?.trim()){if(required)throw new Error('필수 수치를 입력해 주세요.');return null;}const n=Number(values[key]);if(!Number.isFinite(n)||n<0)throw new Error('수치를 확인해 주세요.');return n;};
 async function save(){if(busy)return;setError('');try{
  const body:Record<string,unknown>={};
  if(kind==='sleep'){const duration=(end-start+1440)%1440;if(!duration)throw new Error('취침과 기상 시각을 다르게 선택해 주세요.');const wake=toInstant(day,clock(end));const sleep=new Date(new Date(wake).getTime()-duration*60000).toISOString();Object.assign(body,{startAt:sleep,endAt:wake,totalSleepMinutes:duration});}
  else {Object.assign(body,{bioType:kind,measuredAt:toInstant(day,time)});
   if(kind==='blood_pressure')Object.assign(body,{systolicMmhg:number('systolic'),diastolicMmhg:number('diastolic'),pulseBpm:number('pulse',false)});
   if(kind==='blood_glucose')Object.assign(body,{bloodGlucoseMgDl:number('glucose'),insulinMicroIuMl:number('insulin',false),isFasting:fasting});
   if(kind==='body_composition')Object.assign(body,{weightKg:number('weight'),heightCm:number('height')});
  }
  const encoded=JSON.stringify(body);if(pending.current?.body!==encoded)pending.current={body:encoded,key:createIdempotencyKey()};setBusy(true);
  await healthApi.create(kind==='sleep'?'sleep':'bio',body,pending.current.key);await client.invalidateQueries({queryKey:['health']});onBack();
 }catch(e){setError(e instanceof Error?e.message:'저장하지 못했어요.');}finally{setBusy(false);}}
 if(kind==='water')return <WaterRecords onBack={onBack}/>;
 const bmi=Number(values.weight)>0&&Number(values.height)>0?Number(values.weight)/(Number(values.height)/100)**2:null;
 return <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={[hs.content,{flexGrow:1}]}>
 <View style={hs.row}><Pressable onPress={onBack} accessibilityRole="button" accessibilityLabel="입력 취소" style={hs.back}><Text style={hs.backText}>‹</Text></Pressable><Text style={hs.title}>{labels[kind]}</Text></View>
 <Text style={hs.muted}>{kind==='sleep'?'다이얼을 움직여 취침과 기상 시간을 맞춰보세요.':'측정 시각과 수치를 함께 기록해요.'}</Text>
 <View style={hs.card}><Text style={hs.text}>{kind==='sleep'?'기상 날짜':'기록 시각'}</Text><TextInput accessibilityLabel="기록 날짜" value={day} onChangeText={setDay} placeholder="YYYY-MM-DD" style={hs.input}/>{kind!=='sleep'&&<TextInput accessibilityLabel="기록 시각" value={time} onChangeText={setTime} placeholder="HH:mm" style={hs.input}/>}</View>
 {kind==='sleep'&&<><SleepDial start={start} end={end} onStart={setStart} onEnd={setEnd}/><View style={hs.row}>{(['취침','기상'] as const).map((label,i)=><View key={label} style={hs.field}><Text style={hs.muted}>{label}</Text><View style={hs.row}><Pressable accessibilityLabel={`${label} 5분 이전`} onPress={()=>i?setEnd((end+1435)%1440):setStart((start+1435)%1440)}><Text style={hs.title}>−</Text></Pressable><Text style={hs.text}>{clock(i?end:start)}</Text><Pressable accessibilityLabel={`${label} 5분 이후`} onPress={()=>i?setEnd((end+5)%1440):setStart((start+5)%1440)}><Text style={hs.title}>＋</Text></Pressable></View></View>)}</View></>}
 {kind==='blood_glucose'&&<><Text style={hs.section}>측정 상태</Text><View style={hs.row}>{['공복','식후','일반'].map((label,i)=>{const v=i===0?true:i===1?false:null;return <Pressable key={label} accessibilityRole="radio" accessibilityState={{checked:fasting===v}} onPress={()=>setFasting(v)} style={[hs.pill,hs.spacer,fasting===v&&hs.active]}><Text style={[hs.pillText,fasting===v&&hs.white]}>{label}</Text></Pressable>;})}</View><View style={hs.card}><Field label="혈당" value={values.glucose??''} onChange={set('glucose')} unit="mg/dL"/></View><View style={hs.card}><Field label="인슐린 농도 (선택)" value={values.insulin??''} onChange={set('insulin')} unit="μIU/mL"/></View></>}
 {kind==='blood_pressure'&&<><View style={hs.row}><View style={[hs.card,hs.spacer]}><Field label="수축기" value={values.systolic??''} onChange={set('systolic')} unit="mmHg"/></View><View style={[hs.card,hs.spacer]}><Field label="이완기" value={values.diastolic??''} onChange={set('diastolic')} unit="mmHg"/></View></View><View style={hs.card}><Field label="맥박 (선택)" value={values.pulse??''} onChange={set('pulse')} unit="bpm"/></View></>}
 {kind==='body_composition'&&<><View style={[hs.card,{backgroundColor:'#E0F6F0'}]}><Text style={hs.muted}>현재 BMI</Text><Text style={hs.value}>{format(bmi)}</Text><Text style={hs.muted}>체중과 키를 입력하면 자동으로 계산돼요.</Text></View><View style={hs.card}><Field label="체중" value={values.weight??''} onChange={set('weight')} unit="kg"/><Field label="키" value={values.height??''} onChange={set('height')} unit="cm"/></View></>}
 {!!error&&<Text accessibilityRole="alert" style={hs.error}>{error}</Text>}<View style={{minHeight:25}}/><PrimaryButton label={kind==='sleep'?'수면 기록 저장':'저장하기'} loading={busy} onPress={save}/>
 </ScrollView>;
}

// 작성자: 김진우 — 원본 125mL도 편집 진입 시 보존하며 사용자가 선택한 경우에만 변경한다.
function AmountWheel({value,onChange}:{value:number;onChange:(n:number)=>void}){
 const initial=useRef(value).current;const options=useRef([...new Set([...Array.from({length:200},(_,i)=>(i+1)*10),initial])].sort((a,b)=>a-b)).current;
 const scroll=useRef<ScrollView>(null),positioned=useRef(false);
 const select=(offset:number)=>{const option=options[Math.max(0,Math.min(options.length-1,Math.round(offset/48)))];if(option!==undefined)onChange(option);};
 return <View style={[hs.card,{height:280,overflow:'hidden'}]}><Text style={[hs.muted,{textAlign:'center'}]}>위아래로 밀어 섭취량을 조절하세요</Text><ScrollView ref={scroll} nestedScrollEnabled snapToInterval={48} decelerationRate="fast" showsVerticalScrollIndicator={false} contentContainerStyle={{paddingVertical:72}} onContentSizeChange={()=>{if(!positioned.current){positioned.current=true;scroll.current?.scrollTo({y:options.indexOf(initial)*48,animated:false});}}} onMomentumScrollEnd={e=>select(e.nativeEvent.contentOffset.y)} onScrollEndDrag={e=>select(e.nativeEvent.contentOffset.y)}>{options.map(n=><Pressable key={n} onPress={()=>onChange(n)} accessibilityRole="button" accessibilityLabel={`물 ${n}mL`} style={{height:48,justifyContent:'center',alignItems:'center'}}><Text style={{fontSize:n===value?32:22,color:n===value?'#173A31':'#B0C0BB',fontWeight:n===value?'800':'400'}}>{n}{n===value?' ml':''}</Text></Pressable>)}</ScrollView><Text style={[hs.text,{textAlign:'center'}]}>선택 {format(value)} mL</Text></View>;
}
function WaterRecords({onBack}:{onBack:()=>void}){
 const client=useQueryClient();const [day,setDay]=useState(koreanDay()),[selection,setSelection]=useState(false),[selected,setSelected]=useState<string[]>([]),[editing,setEditing]=useState<HealthRecord|null>(null),[amount,setAmount]=useState(250),[time,setTime]=useState(''),[editDay,setEditDay]=useState(day),[confirm,setConfirm]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const request=useRef<{body:string;key:string}|null>(null);
 const query=useQuery({queryKey:['health','water','records',day],queryFn:async({signal})=>{let cursor:string|undefined,rows:HealthRecord[]=[];do{const page=await healthApi.page('water','7d',signal,{baseDate:day,aggregation:'raw',limit:200,...(cursor?{cursor}:{})});rows.push(...page.records.filter(r=>r.date===day));cursor=page.nextCursor??undefined;if(page.records.some(r=>r.date<day))break;}while(cursor);return rows;},retry:false});
 const rows=query.data??[];const chosen=editing?[editing]:rows.filter(r=>selected.includes(r.recordId)&&r.deletable);const total=rows.reduce((a,r)=>a+(numeric(r,'amount_ml')??0),0);
 async function mutate(mode:'create'|'edit'|'delete',value?:number){if(busy)return;setError('');try{const body=mode==='create'?{consumedAt:toInstant(day,koreanTime(new Date().toISOString())),amountMl:value}:mode==='edit'?{consumedAt:toInstant(editDay,time),amountMl:amount}:{records:chosen.map(r=>({recordId:r.recordId,recordVersion:r.recordVersion}))};const encoded=mode+JSON.stringify(body);if(request.current?.body!==encoded)request.current={body:encoded,key:createIdempotencyKey()};setBusy(true);if(mode==='create')await healthApi.create('water',body,request.current.key);else if(mode==='edit'&&editing)await healthApi.editWater(editing,body,request.current.key);else if(mode==='delete')await healthApi.deleteWater(chosen,request.current.key);request.current=null;setEditing(null);setSelection(false);setSelected([]);setConfirm(false);await client.invalidateQueries({queryKey:['health']});}catch(e){setError(e instanceof Error?e.message:'기록을 변경하지 못했어요.');}finally{setBusy(false);}}
 return <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={hs.content}><View style={hs.between}><View style={hs.row}><Pressable accessibilityRole="button" accessibilityLabel="물 기록 뒤로" style={hs.back} onPress={()=>{if(busy)return;if(editing)setEditing(null);else if(selection)setSelection(false);else onBack();}}><Text style={hs.backText}>‹</Text></Pressable><Text style={hs.title}>{editing?'기록 편집':selection?'기록 선택':'물 섭취 기록'}</Text></View><Pressable accessibilityRole="button" disabled={busy} onPress={()=>editing?setConfirm(true):setSelection(v=>!v)}><Text style={hs.pillText}>{editing?'삭제':selection?'취소':'선택'}</Text></Pressable></View>
 {editing?<><View style={hs.card}><Text style={hs.text}>섭취 시각</Text><TextInput accessibilityLabel="물 기록 날짜" style={hs.input} value={editDay} onChangeText={setEditDay}/><TextInput accessibilityLabel="물 기록 시각" style={hs.input} value={time} onChangeText={setTime}/></View><Text style={hs.section}>물 섭취량</Text><AmountWheel key={editing.recordId} value={amount} onChange={setAmount}/><PrimaryButton label="변경사항 저장" loading={busy} onPress={()=>mutate('edit')}/></>:<>
 <TextInput accessibilityLabel="물 조회 날짜" style={hs.input} value={day} onChangeText={setDay}/><View style={[hs.card,{backgroundColor:'#FFF0E2'}]}><Text style={hs.text}>{selection?`${chosen.length}개 선택됨`:`${day} 총 섭취량`}</Text><Text style={[hs.value,{color:'#F07343'}]}>{format(selection?chosen.reduce((a,r)=>a+(numeric(r,'amount_ml')??0),0):total)} mL</Text></View>
 {!selection&&<><Text style={hs.section}>빠르게 추가</Text><View style={hs.row}>{[125,250,500].map((n,i)=><Pressable accessibilityRole="button" disabled={busy} key={n} onPress={()=>mutate('create',n)} style={[hs.pill,hs.spacer]}><Text style={hs.pillText}>{[.5,1,2][i]}잔 ({n}ml)</Text></Pressable>)}</View></>}
 <View style={hs.between}><Text style={hs.section}>섭취 기록 {rows.length}회</Text>{selection&&<Pressable accessibilityRole="button" onPress={()=>setSelected(rows.filter(r=>r.deletable).map(r=>r.recordId))}><Text style={hs.pillText}>전체 선택</Text></Pressable>}</View>
 {query.isPending&&<Text style={hs.muted}>기록을 불러오고 있어요.</Text>}{query.isError&&<Text accessibilityRole="alert" style={hs.error}>기록을 불러오지 못했어요.</Text>}{!query.isPending&&!query.isError&&!rows.length&&<Text style={hs.muted}>아직 기록이 없어요.</Text>}
 {rows.map(r=><Pressable key={r.recordId} accessibilityRole={selection?'checkbox':'button'} accessibilityState={{disabled:selection?!r.deletable:!r.editable,checked:selection?selected.includes(r.recordId):undefined}} disabled={busy||(selection?!r.deletable:!r.editable)} onPress={()=>selection?setSelected(v=>v.includes(r.recordId)?v.filter(id=>id!==r.recordId):[...v,r.recordId]):(setEditing(r),setAmount(numeric(r,'amount_ml')??250),setTime(koreanTime(r.measuredAt)),setEditDay(r.date))} style={[hs.card,hs.between]}><View><Text style={hs.text}>{selection?(selected.includes(r.recordId)?'● ':'○ '):''}섭취량</Text><Text style={hs.muted}>{koreanTime(r.measuredAt)} · {r.source==='manual'?'앱 입력':'삼성헬스 · 편집 불가'}</Text></View><Text style={hs.value}>{format(numeric(r,'amount_ml'))} <Text style={hs.muted}>ml</Text></Text></Pressable>)}
 {selection?<PrimaryButton label={`선택한 기록 ${chosen.length}개 삭제`} disabled={!chosen.length} loading={busy} onPress={()=>setConfirm(true)}/>:<PrimaryButton label="입력 완료" onPress={onBack}/>}
 </>}{!!error&&<Text accessibilityRole="alert" style={hs.error}>{error}</Text>}
 <ConfirmModal visible={confirm} title="물 기록을 삭제할까요?" description="선택한 앱 입력을 삭제합니다. 연결된 삼성헬스 원본이 있으면 원본이 다시 표시돼요." confirmLabel="삭제" tone="danger" pending={busy} error={error} onCancel={()=>setConfirm(false)} onConfirm={()=>mutate('delete')}/>
 </ScrollView>;
}
