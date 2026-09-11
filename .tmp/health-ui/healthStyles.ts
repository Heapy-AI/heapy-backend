import { StyleSheet } from 'react-native';
import { colors } from '../../shared/theme/tokens';
export const hs=StyleSheet.create({
  root:{flex:1,minHeight:0},content:{paddingHorizontal:20,paddingTop:12,paddingBottom:28,gap:16,width:'100%',maxWidth:480,alignSelf:'center'},
  row:{flexDirection:'row',alignItems:'center',gap:10},between:{flexDirection:'row',alignItems:'center',justifyContent:'space-between',gap:8},
  title:{fontSize:23,fontWeight:'800',color:colors.text},section:{fontSize:15,fontWeight:'700',color:colors.text},text:{fontSize:13,lineHeight:20,color:colors.text},muted:{fontSize:11,lineHeight:17,color:colors.textMuted},
  card:{backgroundColor:colors.surface,borderRadius:24,padding:16,gap:12,borderWidth:1,borderColor:colors.line,boxShadow:'0 5px 18px rgba(15,69,51,0.07)'},
  pill:{minHeight:40,paddingHorizontal:14,paddingVertical:9,borderRadius:22,backgroundColor:'#E8F2ED',justifyContent:'center',alignItems:'center'},active:{backgroundColor:colors.primary},pillText:{fontSize:12,fontWeight:'700',color:colors.primaryDark},white:{color:'#FFFFFF'},
  metric:{flex:1,minWidth:0,padding:12,borderRadius:20,backgroundColor:colors.surface,gap:8},value:{fontSize:22,fontWeight:'800',color:colors.primaryDark},
  error:{fontSize:12,lineHeight:18,color:colors.danger},input:{borderWidth:1,borderColor:colors.line,borderRadius:18,padding:14,fontSize:16,color:colors.text,backgroundColor:'#FFFFFF',minHeight:48},
  field:{gap:7,flex:1},spacer:{flex:1},back:{minWidth:36,minHeight:44,alignItems:'center',justifyContent:'center'},backText:{fontSize:30,color:colors.text},
});
