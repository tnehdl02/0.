package com.example.binancephonescanner

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/** Paper-scalping simulator. Scanner detection and service code are intentionally untouched. */
class HistoryActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("paper_scalper", MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
    private val bg = Color.rgb(8, 16, 31)
    private val fg = Color.rgb(238, 246, 255)
    private val muted = Color.rgb(164, 185, 207)
    private var busy = false
    private lateinit var root: LinearLayout
    private lateinit var watch: Watch

    data class Trade(val symbol:String,val detected:Long,val entry:Double,val stop:Double,val target:Double,val exit:Double,val exitAt:Long,val result:String,val reason:String) {
        val duration:Long get() = if (exitAt > 0) max(0L, exitAt-detected) else 0L
        val grossPct:Double get() = if(exit>0 && entry>0) (exit-entry)/entry*100.0 else 0.0
        val netPct:Double get() = grossPct - 0.10 // Futures market taker fee estimate: 0.05% each side; no slippage.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.ensureLoaded(this)
        val id = intent.getStringExtra(ScannerService.EXTRA_WATCH_ID)
        watch = Watch.values().firstOrNull { it.id == id } ?: run { finish(); return }
        render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(22),dp(18),dp(28)) }
        scroll.addView(root); setContentView(scroll)
        root.addView(button("← 돌아가기", Color.rgb(52,84,112)) { finish() }, lp(-1,dp(42)))
        root.addView(label("모의 스캘핑 기록",26f,fg,true), margin(0,14,0,4))
        root.addView(label("${watch.name} · 감지된 순서대로 시뮬레이션 · 한국 시간",12f,muted,false), margin(0,0,0,14))

        val capital = numberPref("capital", 100000.0)
        val capitalInput = EditText(this).apply { setText(if (capital > 0) capital.toLong().toString() else "100000"); hint="원금 (USDT)"; setTextColor(fg); setHintTextColor(muted); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setPadding(dp(12),dp(8),dp(12),dp(8)); background=rounded(Color.rgb(20,34,53)) }
        root.addView(label("시작 원금 (USDT)",14f,fg,true), margin(0,0,0,5)); root.addView(capitalInput, lp(-1,dp(48)))

        val stopMode = prefs.getString("stopMode", "previous") ?: "previous"
        val stopGroup = RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
        val prevRadio = RadioButton(this).apply { text="손절: 감지 순간 봉 기준 n봉 전 봉의 저가"; setTextColor(fg); id=1; isChecked=stopMode=="previous" }
        val pctRadio = RadioButton(this).apply { text="손절: 진입가 대비 고정 하락률"; setTextColor(fg); id=2; isChecked=stopMode=="percent" }
        stopGroup.addView(prevRadio); stopGroup.addView(pctRadio)
        root.addView(label("손절 방식",15f,fg,true), margin(0,14,0,2)); root.addView(stopGroup)
        val nInput = numericInput(prefs.getInt("nBars",1).toString(),"n봉 전 (예: 1)")
        val stopPctInput = numericInput(prefs.getFloat("stopPct",2f).toString(),"고정 손절률 (%) 예: 2")
        root.addView(label("과거 봉 개수 n (n=1은 직전 완료된 1시간 봉)",12f,muted,false), margin(0,2,0,4)); root.addView(nInput, lp(-1,dp(44)))
        root.addView(label("고정 손절 하락률 (%)",12f,muted,false), margin(0,8,0,4)); root.addView(stopPctInput, lp(-1,dp(44)))
        val tpInput = numericInput(prefs.getFloat("tpPct",3f).toString(),"익절 상승률 (%) 예: 3")
        root.addView(label("익절: 진입가 대비 n% 상승",15f,fg,true), margin(0,14,0,4)); root.addView(tpInput, lp(-1,dp(44)))
        root.addView(label("수수료: USDⓈ-M 선물 시장가 가정 0.05% 진입 + 0.05% 청산 · 슬리피지 제외",11f,muted,false), margin(0,7,0,8))
        root.addView(button(if(busy) "계산 중…" else "설정 저장 및 모의매매 계산",Color.rgb(24,133,115)) {
            val cap=capitalInput.text.toString().toDoubleOrNull() ?: 0.0
            val n=nInput.text.toString().toIntOrNull() ?: 1
            val sl=stopPctInput.text.toString().toDoubleOrNull() ?: 2.0
            val tp=tpInput.text.toString().toDoubleOrNull() ?: 3.0
            if(cap<=0 || n<1 || sl<=0 || tp<=0) { toast("원금과 비율은 0보다 커야 하며 n은 1 이상이어야 합니다."); return@button }
            prefs.edit().putFloat("capital",cap.toFloat()).putString("stopMode",if(prevRadio.isChecked) "previous" else "percent").putInt("nBars",n).putFloat("stopPct",sl.toFloat()).putFloat("tpPct",tp.toFloat()).apply()
            calculate(cap,if(prevRadio.isChecked) "previous" else "percent",n,sl,tp)
        }, margin(0,5,0,12))
        val saved = loadTrades()
        showSummary(saved, capital)
        root.addView(button("기록 전체 삭제",Color.rgb(151,55,70)) {
            AlertDialog.Builder(this).setTitle("모의매매 기록 전체 삭제").setMessage("저장된 모의매매 결과를 모두 삭제할까요? 감시 기록은 삭제되지 않습니다.").setNegativeButton("취소",null).setPositiveButton("전체 삭제") { _,_-> prefs.edit().remove("trades").remove("startedAt").apply(); render() }.show()
        }, margin(0,10,0,14))
        root.addView(label("거래 기록 (${saved.size}건)",18f,fg,true), margin(0,0,0,8))
        if(saved.isEmpty()) root.addView(cardText("아직 모의매매 기록이 없습니다. 설정을 저장하고 계산을 실행하세요."))
        saved.forEachIndexed { index,t -> root.addView(tradeCard(index+1,t), margin(0,0,0,9)) }
    }

    private fun calculate(capital:Double, mode:String, n:Int, slPct:Double, tpPct:Double) {
        if(busy)return; busy=true; render()
        Thread {
            val detections = ScanHistory.load(this,watch).flatMap { it.matches }.distinctBy { "${it.symbol}|${it.observedAt}" }.sortedBy { it.observedAt }
            val trades=ArrayList<Trade>()
            for(m in detections) {
                if(m.price<=0 || m.observedAt<=0) continue
                try {
                    val entry=m.price; val stop=if(mode=="percent") entry*(1-slPct/100.0) else previousLow(m.symbol,m.observedAt,n)
                    if(stop<=0 || stop>=entry) continue
                    val target=entry*(1+tpPct/100.0)
                    val outcome=scanExit(m.symbol,m.observedAt,stop,target)
                    trades.add(Trade(m.symbol,m.observedAt,entry,stop,target,outcome.first,outcome.second,outcome.third,outcome.fourth))
                } catch (_:Exception) { trades.add(Trade(m.symbol,m.observedAt,m.price,if(mode=="percent")m.price*(1-slPct/100) else 0.0,m.price*(1+tpPct/100),0.0,0L,"진행 중","가격 데이터 조회 실패")) }
            }
            val arr=JSONArray(); trades.forEach { t -> arr.put(JSONObject().apply { put("s",t.symbol);put("d",t.detected);put("e",t.entry);put("sl",t.stop);put("tp",t.target);put("x",t.exit);put("xt",t.exitAt);put("r",t.result);put("why",t.reason) }) }
            prefs.edit().putString("trades",arr.toString()).putLong("startedAt",detections.minOfOrNull { it.observedAt } ?: System.currentTimeMillis()).apply()
            runOnUiThread { busy=false; render() }
        }.start()
    }

    private fun previousLow(symbol:String, observed:Long, n:Int):Double {
        val hour=3600000L; val candleStart=observed/hour*hour; val start=candleStart-n*hour
        val rows=apiKlines(symbol,"1h",start,candleStart-1, n.coerceAtMost(1000))
        if(rows.length()==0) return 0.0
        return rows.getJSONArray(rows.length()-1).getString(3).toDouble()
    }
    private fun scanExit(symbol:String, detected:Long, stop:Double, target:Double):Quad {
        var start=detected/60000*60000; val end=System.currentTimeMillis(); var pages=0
        while(start<end && pages<30) {
            val rows=apiKlines(symbol,"1m",start,end,1000); if(rows.length()==0) break
            for(i in 0 until rows.length()) {
                val k=rows.getJSONArray(i); val ts=k.getLong(0); if(ts<detected)continue
                val low=k.getString(3).toDouble(); val high=k.getString(2).toDouble()
                // Conservative tie-break: if both levels are crossed in one candle, assume stop first.
                if(low<=stop) return Quad(stop,ts,"손절","손절가 도달")
                if(high>=target) return Quad(target,ts,"익절","익절가 도달")
            }
            val last=rows.getJSONArray(rows.length()-1).getLong(0)
            if(rows.length()<1000 || last+60000<=start) break
            start=last+60000; pages++
        }
        val price=try { apiPrice(symbol) } catch(_:Exception){0.0}
        return Quad(price,0L,"진행 중","익절·손절 미도달")
    }
    private data class Quad(val first:Double,val second:Long,val third:String,val fourth:String)
    private fun apiKlines(symbol:String, interval:String, start:Long, end:Long, limit:Int):JSONArray {
        val url="https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$interval&startTime=$start&endTime=$end&limit=$limit"
        val c=URL(url).openConnection() as HttpURLConnection; c.connectTimeout=12000;c.readTimeout=20000
        try { if(c.responseCode !in 200..299) throw IllegalStateException("Binance HTTP ${c.responseCode}"); return JSONArray(c.inputStream.bufferedReader().use{it.readText()}) } finally { c.disconnect() }
    }
    private fun apiPrice(symbol:String):Double { val c=URL("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol").openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=10000;try{return JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optDouble("price",0.0)}finally{c.disconnect()} }

    private fun loadTrades():List<Trade> { val a=try{JSONArray(prefs.getString("trades","[]"))}catch(_:Exception){JSONArray()};return (0 until a.length()).map { val o=a.getJSONObject(it);Trade(o.optString("s"),o.optLong("d"),o.optDouble("e"),o.optDouble("sl"),o.optDouble("tp"),o.optDouble("x"),o.optLong("xt"),o.optString("r"),o.optString("why")) } }
    private fun showSummary(trades:List<Trade>, initial:Double) {
        val closed=trades.filter { it.exit>0 && it.exitAt>0 }; var balance=initial; var wins=0;var losses=0
        closed.sortedBy { it.detected }.forEach { t -> val net=t.netPct/100.0; balance*=1+net; if(net>0)wins++ else losses++ }
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(14));background=rounded(Color.rgb(19,34,53)) }
        card.addView(label("복리 결과",17f,fg,true)); card.addView(label("시작 원금  ${money(initial)} USDT",13f,muted,false),margin(0,7,0,0));card.addView(label("현재/최종 자산  ${money(balance)} USDT",19f,if(balance>=initial) Color.rgb(80,238,150) else Color.rgb(255,99,99),true),margin(0,5,0,0));card.addView(label("총수익률  ${pct((balance/initial-1)*100)}",15f,fg,true),margin(0,4,0,0))
        val total= wins+losses;card.addView(label("익절 ${wins}회 · 손절 ${losses}회 · 승률 ${if(total>0) String.format(Locale.US,"%.2f",wins*100.0/total) else "0.00"}%",14f,fg,true),margin(0,8,0,0))
        val started=prefs.getLong("startedAt",trades.minOfOrNull{it.detected}?:0L);val end=if(trades.any{it.result=="진행 중"})System.currentTimeMillis() else trades.maxOfOrNull{it.exitAt}?:System.currentTimeMillis()
        card.addView(label("감시 기간: ${if(started>0)timeFmt.format(Date(started)) else "-"} ~ ${timeFmt.format(Date(end))}",12f,muted,false),margin(0,5,0,0));root.addView(card,margin(0,14,0,0))
    }
    private fun tradeCard(i:Int,t:Trade):LinearLayout { val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(12),dp(13),dp(12));background=rounded(Color.rgb(19,34,53))};val col=if(t.result=="익절")Color.rgb(80,238,150) else if(t.result=="손절")Color.rgb(255,99,99) else fg
        c.addView(label("$i. ${t.symbol}  (${timeFmt.format(Date(t.detected))})",16f,fg,true));c.addView(label("진입가 ${price(t.entry)} · 손절가 ${price(t.stop)} · 익절가 ${price(t.target)}",12f,muted,false),margin(0,6,0,0));c.addView(label("결과: ${t.result} · ${t.reason}",14f,col,true),margin(0,5,0,0));if(t.exit>0)c.addView(label("청산가 ${price(t.exit)} · 순손익(수수료 반영) ${pct(t.netPct)}",12f,col,false),margin(0,4,0,0));c.addView(label("소요 시간: ${if(t.exitAt>0)duration(t.exitAt-t.detected) else "진행 중"}",12f,muted,false),margin(0,4,0,0));return c }
    private fun duration(ms:Long):String { val s=ms/1000;return if(s<3600) "${s/60}분 ${s%60}초" else if(s<86400) "${s/3600}시간 ${(s%3600)/60}분" else "${s/86400}일 ${(s%86400)/3600000}시간" }
    private fun numberPref(k:String,d:Double)=prefs.getFloat(k,d.toFloat()).toDouble()
    private fun numericInput(v:String,h:String)=EditText(this).apply{setText(v);hint=h;setTextColor(fg);setHintTextColor(muted);inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;setPadding(dp(12),dp(6),dp(12),dp(6));background=rounded(Color.rgb(20,34,53))}
    private fun button(s:String,color:Int,onClick:()->Unit)=Button(this).apply{text=s;setTextColor(Color.WHITE);textSize=13f;background=rounded(color);setOnClickListener{onClick()}}
    private fun label(s:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)setTypeface(null,android.graphics.Typeface.BOLD)}
    private fun cardText(s:String)=LinearLayout(this).apply{setPadding(dp(14),dp(14),dp(14),dp(14));background=rounded(Color.rgb(19,34,53));addView(label(s,13f,muted,false))}
    private fun rounded(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(13).toFloat()}
    private fun lp(w:Int,h:Int)=LinearLayout.LayoutParams(w,h)
    private fun margin(l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun money(v:Double)=String.format(Locale.US,"%,.4f",v)
    private fun price(v:Double)=if(v<=0) "미확인" else String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')
    private fun pct(v:Double)=String.format(Locale.US,"%+.2f%%",v)
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
