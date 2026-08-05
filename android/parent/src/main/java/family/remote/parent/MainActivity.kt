package family.remote.parent
import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import family.remote.parent.capture.ScreenShareService
import family.remote.protocol.RendezvousClient

object ParentSessionState{@Volatile var client:RendezvousClient?=null}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);family.remote.parent.capture.AppContext.value=applicationContext;setContent{MaterialTheme{ParentApp()}}}
@Composable private fun ParentApp(){var code by remember{mutableStateOf<String?>(null)};var request by remember{mutableStateOf<Pair<String,String>?>(null)};var status by remember{mutableStateOf("Tap Start support when you are ready.")};var accepted by remember{mutableStateOf(false)};val client=remember{RendezvousClient(object:RendezvousClient.Listener{override fun onOpen(){runOnUiThread{ParentSessionState.client?.createRoom()}};override fun onRoomCreated(value:String,expiresAt:Long){runOnUiThread{code=value;status="Code expires in 10 minutes."}};override fun onJoinRequest(id:String,helperName:String){runOnUiThread{request=id to helperName;status="$helperName is requesting access."}};override fun onAccepted(expiresAt:Long){runOnUiThread{accepted=true}};override fun onEnded(reason:String){runOnUiThread{code=null;request=null;status="Session ended."}};override fun onError(value:String){runOnUiThread{status=if(value=="service_unavailable")"Could not reach support service. Try again." else "Request failed: $value"}}})}
DisposableEffect(client){ParentSessionState.client=client;onDispose{client.close();ParentSessionState.client=null}}
val projection=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->val data=result.data;if(result.resultCode==RESULT_OK&&data!=null&&accepted){ContextCompat.startForegroundService(this,Intent(this,ScreenShareService::class.java).putExtra(ScreenShareService.EXTRA_RESULT_CODE,result.resultCode).putExtra(ScreenShareService.EXTRA_RESULT_DATA,data).putExtra("sessionId","ephemeral"))};accepted=false};LaunchedEffect(accepted){if(accepted)projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())};val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("KinPilot Parent",style=MaterialTheme.typography.headlineMedium);Text("Nothing is saved. You approve every helper and Android asks before sharing your screen.");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Accessibility")};Button({notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Notifications")}};Button(enabled=code==null,onClick={status="Waking support service…";client.connect()}){Text("Start support")};code?.let{value->Text(value,style=MaterialTheme.typography.headlineSmall);Image(qr("https://kinpilot.netlify.app/join#$value").asImageBitmap(),"QR support code",Modifier.size(220.dp));Text("Share this one-time QR or code. It can connect one helper only.")};request?.let{(id,name)->Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("$name wants to help",style=MaterialTheme.typography.titleMedium);Text("Only accept if you recognize this person.");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({client.respond(id,false);request=null}){Text("Decline")};Button({client.respond(id,true);request=null}){Text("Accept")}}}}};Text(status);if(code!=null)OutlinedButton({client.close();code=null;request=null;status="Session cancelled."}){Text("Cancel")}}
}
private fun qr(text:String):Bitmap{val matrix=MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,512,512);return Bitmap.createBitmap(512,512,Bitmap.Config.RGB_565).also{bitmap->for(y in 0 until 512)for(x in 0 until 512)bitmap.setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}}
