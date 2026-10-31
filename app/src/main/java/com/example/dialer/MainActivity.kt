package com.example.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class Contact(val name: String, val number: String)
data class Call(val type: Int, val date: Long, val dur: Long)
data class Rec(val name: String, val uri: Uri, val time: Long, val size: Long)

val PERMS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)
fun tail(s: String, n: Int = 10) = s.filter { it.isDigit() }.takeLast(n)
val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

fun loadContacts(c: Context): List<Contact> {
    val out = LinkedHashMap<String, Contact>()
    c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use {
        while (it.moveToNext()) {
            val n = it.getString(1) ?: continue
            out.putIfAbsent(tail(n), Contact(it.getString(0) ?: n, n))
        }
    }
    return out.values.toList()
}

fun loadCalls(c: Context, number: String): List<Call> {
    val t = tail(number); val out = mutableListOf<Call>()
    c.contentResolver.query(CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
        null, null, "date DESC")?.use {
        while (it.moveToNext()) if (tail(it.getString(0) ?: "") == t) out += Call(it.getInt(1), it.getLong(2), it.getLong(3))
    }
    return out
}

// Запись относится к номеру, если в имени файла есть последние 7 цифр номера
// или файл создан в пределах 3 минут после окончания звонка с этим номером.
fun loadRecs(c: Context, tree: Uri?, number: String, calls: List<Call>): List<Rec> {
    if (tree == null) return emptyList()
    val dir = DocumentFile.fromTreeUri(c, tree) ?: return emptyList()
    val t7 = tail(number, 7)
    return dir.listFiles().filter { f ->
        val n = f.name ?: ""
        f.isFile && (f.type?.startsWith("audio") == true || n.substringAfterLast('.', "").lowercase() in listOf("oga", "opus", "flac", "m4a", "mp3", "amr", "wav", "aac", "ogg", "3gp")) &&
            (tail(n).contains(t7) || calls.any { Math.abs(f.lastModified() - (it.date + it.dur * 1000)) < 180_000 })
    }.map { Rec(it.name ?: "Запись", it.uri, it.lastModified(), it.length()) }.sortedByDescending { it.time }
}

fun typeName(t: Int) = when (t) { 1 -> "Входящий"; 2 -> "Исходящий"; 3 -> "Пропущенный"; 5 -> "Отклонённый"; else -> "Звонок" }
fun dur(s: Long) = "%02d:%02d".format(s / 60, s % 60)
fun size(b: Long) = if (b > 1_048_576) "%.1f МБ".format(b / 1048576.0) else "${b / 1024} КБ"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    var granted by remember { mutableStateOf(PERMS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) }
    var tree by remember { mutableStateOf(prefs.getString("tree", null)?.let { Uri.parse(it) }) }
    var sel by remember { mutableStateOf<Contact?>(null) }
    var all by remember { mutableStateOf(false) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.all { it } }
    val treeL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("tree", u.toString()).apply(); tree = u
        }
    }
    if (!granted) {
        Column(Modifier.padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Нужен доступ к контактам, журналу и звонкам")
            Button(onClick = { permL.launch(PERMS) }) { Text("Разрешить") }
        }
        return
    }
    fun call(n: String) = ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))))
    val s = sel
    if (s == null) {
        val contactsState by produceState<List<Contact>?>(null) { value = withContext(Dispatchers.IO) { loadContacts(ctx) } }
        val contacts = contactsState ?: emptyList()
        var q by remember { mutableStateOf("") }
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true)
            TextButton(onClick = { treeL.launch(null) }) { Text(if (tree == null) "Выбрать папку с записями" else "Сменить папку с записями") }
            if (contactsState == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LazyColumn {
                items(contacts.filter { it.name.contains(q, true) || it.number.contains(q) }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { sel = c; all = false }.padding(vertical = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(c.number, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    } else {
        BackHandler { if (all) all = false else sel = null }
        val data by produceState<Pair<List<Call>, List<Rec>>?>(null, s, tree) {
            value = withContext(Dispatchers.IO) { val c = loadCalls(ctx, s.number); c to loadRecs(ctx, tree, s.number, c) }
        }
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
        val calls = d.first
        val recs = d.second
        LazyColumn(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(s.name, style = MaterialTheme.typography.headlineSmall); Text(s.number) }
                    Button(onClick = { call(s.number) }) { Text("Позвонить") }
                }
            }
            if (!all) {
                item { Text("История звонков · ${calls.size}", style = MaterialTheme.typography.titleLarge) }
                items(calls.take(10)) { Column { Text(typeName(it.type)); Text("${fmt.format(Date(it.date))} · ${dur(it.dur)}") } }
            }
            item { Text("Записи разговоров · ${recs.size}", style = MaterialTheme.typography.titleLarge) }
            if (tree == null) item { Button(onClick = { treeL.launch(null) }) { Text("Выбрать папку с записями") } }
            items(if (all) recs else recs.take(2)) { r ->
                Card(Modifier.fillMaxWidth().clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Column(Modifier.padding(12.dp)) { Text(fmt.format(Date(r.time))); Text("${r.name} · ${size(r.size)} ▶") } }
            }
            if (!all && recs.size > 2) item { TextButton(onClick = { all = true }) { Text("Все записи") } }
        }
        }
    }
}
