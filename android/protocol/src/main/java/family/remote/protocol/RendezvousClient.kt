package family.remote.protocol

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class RendezvousClient(private val listener:Listener,private val url:String=DEFAULT_URL):AutoCloseable{
private val http=OkHttpClient();private val handler=Handler(Looper.getMainLooper());private val json="application/json".toMediaType();@Volatile private var clientId:String?=null;@Volatile private var polling=false;@Volatile var signalListener:((String,String)->Unit)?=null
fun connect(){request("/api/connect",JSONObject()){response->clientId=response.getString("clientId");polling=true;listener.onOpen();poll()}}
fun createRoom()=send(JSONObject().put("type","create"));fun join(code:String,name:String)=send(JSONObject().put("type","join").put("code",code).put("helperName",name.take(60)));fun respond(id:String,accept:Boolean)=send(JSONObject().put("type","respond").put("requestId",id).put("accept",accept));fun signal(kind:String,payload:String){if(payload.length<=32*1024)send(JSONObject().put("type","signal").put("kind",kind).put("payload",payload))}
private fun poll(){val id=clientId?:return;if(!polling)return;http.newCall(Request.Builder().url("$url/api/poll?clientId=$id").build()).enqueue(object:Callback{override fun onFailure(call:Call,e:IOException)=again();override fun onResponse(call:Call,response:Response){response.use{if(it.isSuccessful)runCatching{JSONObject(it.body?.string().orEmpty()).getJSONArray("messages")}.getOrNull()?.let{messages->for(i in 0 until messages.length())dispatch(messages.getJSONObject(i))}};again()}})}
private fun again(){if(polling)handler.postDelayed(::poll,700)}
private fun send(message:JSONObject){val id=clientId?:return;request("/api/message",JSONObject().put("clientId",id).put("message",message)){} }
private fun request(path:String,body:JSONObject,success:(JSONObject)->Unit){http.newCall(Request.Builder().url(url+path).post(body.toString().toRequestBody(json)).build()).enqueue(object:Callback{override fun onFailure(call:Call,e:IOException)=listener.onError("service_unavailable");override fun onResponse(call:Call,response:Response){response.use{if(!it.isSuccessful){listener.onError("service_unavailable");return};val parsed:JSONObject=try{JSONObject(it.body?.string().orEmpty())}catch(e:org.json.JSONException){listener.onError("service_unavailable");return};success(parsed)}}})}
private fun dispatch(v:JSONObject){when(v.optString("type")){"room-created"->listener.onRoomCreated(v.getString("code"),v.getLong("expiresAt"));"join-request"->listener.onJoinRequest(v.getString("requestId"),v.optString("helperName","Family helper"));"waiting"->listener.onWaiting();"accepted"->listener.onAccepted(v.getLong("expiresAt"));"declined"->listener.onDeclined();"signal"->signalListener?.invoke(v.getString("kind"),v.getString("payload"));"ended"->listener.onEnded(v.optString("reason","ended"));"error"->listener.onError(v.optString("code","unknown"))}}
override fun close(){polling=false;handler.removeCallbacksAndMessages(null);send(JSONObject().put("type","leave"));clientId=null}
interface Listener{fun onOpen(){};fun onRoomCreated(code:String,expiresAt:Long){};fun onJoinRequest(requestId:String,helperName:String){};fun onWaiting(){};fun onAccepted(expiresAt:Long){};fun onDeclined(){};fun onEnded(reason:String){};fun onError(code:String){}}
companion object{const val DEFAULT_URL="https://kinpilot-rendezvous.onrender.com"}}
