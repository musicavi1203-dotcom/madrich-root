package com.example.rootguide

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val ACTION_USB_PERMISSION = "com.example.rootguide.USB_PERMISSION"

// קודי הודעות ADB (מחרוזות ASCII בסדר little-endian)
private const val A_CNXN = 0x4e584e43
private const val A_AUTH = 0x48545541
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_CLSE = 0x45534c43
private const val A_WRTE = 0x45545257

private const val ADB_VERSION = 0x01000000
private const val MAX_DATA = 4096
private const val AUTH_SIGNATURE = 2
private const val AUTH_PUBLIC_KEY = 3
private const val LOCAL_ID = 1
private const val TIMEOUT_NORMAL = 15000
private const val TIMEOUT_USER_ACCEPT = 120000
private const val WRITE_TIMEOUT = 5000
private const val READ_CHUNK = 16384
private const val MAX_WRITE_CHUNK = 16384

// DigestInfo של SHA-1 לפני ה-hash של האסימון (נדרש לחתימת AUTH)
private val SHA1_DIGEST_INFO = byteArrayOf(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
)

data class DeviceInfo(
    val model: String,
    val android: String,
    val chipset: String,
    val build: String
)

enum class RebootTarget(val service: String) {
    SYSTEM("reboot:"),
    RECOVERY("reboot:recovery"),
    BOOTLOADER("reboot:bootloader"),
    FASTBOOT("reboot:bootloader")
}

class AdbController(private val context: Context) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var session: AdbSession? = null

    suspend fun connect(): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        closeSession()
        try {
            val device = findAdbDevice()
                ?: throw IOException(context.getString(R.string.err_no_adb_device))
            if (!usb.hasPermission(device) && !requestPermission(device)) {
                throw IOException(context.getString(R.string.err_permission))
            }
            val iface = findAdbInterface(device)
                ?: throw IOException(context.getString(R.string.err_no_adb_device))
            val conn = usb.openDevice(device)
                ?: throw IOException(context.getString(R.string.err_permission))

            val s = AdbSession(UsbTransport(conn, iface))
            try {
                s.handshake(AdbKeys.load(context))
                session = s
                Result.success(
                    DeviceInfo(
                        model = s.shell("getprop ro.product.model"),
                        android = s.shell("getprop ro.build.version.release"),
                        chipset = s.shell("getprop ro.board.platform"),
                        build = s.shell("getprop ro.build.display.id")
                    )
                )
            } catch (e: Exception) {
                s.close()
                throw e
            }
        } catch (e: Exception) {
            Result.failure<DeviceInfo>(e)
        }
    }

    suspend fun runShell(command: String): Result<String> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext Result.failure<String>(notConnected())
        try {
            Result.success(s.shell(command))
        } catch (e: Exception) {
            Result.failure<String>(e)
        }
    }

    suspend fun reboot(target: RebootTarget): Result<Unit> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext Result.failure<Unit>(notConnected())
        // המכשיר מתנתק בזמן ההפעלה מחדש, לכן כשל בקריאה אחרי השליחה צפוי
        runCatching { s.open(target.service) }
        closeSession()
        Result.success(Unit)
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun notConnected() =
        IllegalStateException(context.getString(R.string.err_not_connected))

    private fun findAdbDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { findAdbInterface(it) != null }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
            it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01
        }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED
            )
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
}

private class AdbMessage(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

private class AdbSession(private val transport: UsbTransport) {

    private var nextLocalId = 1

    fun handshake(keys: AdbKeys) {
        send(A_CNXN, ADB_VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var msg = recv(TIMEOUT_NORMAL)
        var attempts = 0
        while (msg.cmd == A_AUTH) {
            attempts++
            when (attempts) {
                1 -> send(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(msg.data))
                2 -> send(A_AUTH, AUTH_PUBLIC_KEY, 0, keys.publicKeyAdb())
                else -> throw IOException("ADB auth rejected")
            }
            msg = recv(TIMEOUT_USER_ACCEPT)
        }
        if (msg.cmd != A_CNXN) throw IOException("unexpected reply to CNXN")
    }

    fun shell(command: String): String = open("shell:$command")

    fun open(service: String): String {
        // מזהה חדש לכל פקודה, כדי שאישורים ישנים לא יתערבבו
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, "$service\u0000".toByteArray())
        var msg = recvFor(localId, TIMEOUT_NORMAL)
        if (msg.cmd == A_CLSE) throw IOException("device refused $service")
        if (msg.cmd != A_OKAY) throw IOException("unexpected reply to OPEN")
        val remoteId = msg.arg0

        val out = ByteArrayOutputStream()
        var done = false
        while (!done) {
            msg = recvFor(localId, TIMEOUT_NORMAL)
            when (msg.cmd) {
                A_WRTE -> {
                    out.write(msg.data)
                    send(A_OKAY, localId, remoteId)
                }
                A_CLSE -> done = true
            }
        }
        runCatching { send(A_CLSE, localId, remoteId) }
        return String(out.toByteArray(), Charsets.UTF_8).trim()
    }

    // מדלג על הודעות שלא מיועדות למזהה הנוכחי
    private fun recvFor(localId: Int, timeout: Int): AdbMessage {
        var msg = recv(timeout)
        while (msg.arg1 != localId) msg = recv(timeout)
        return msg
    }

    fun close() = transport.close()

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd).putInt(arg0).putInt(arg1)
            .putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xFF })
            .putInt(cmd.inv())
        transport.write(header.array())
        if (data.isNotEmpty()) transport.write(data)
    }

    private fun recv(timeout: Int): AdbMessage {
        val h = ByteBuffer.wrap(transport.read(24, timeout)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        val data = if (len > 0) transport.read(len, timeout) else ByteArray(0)
        return AdbMessage(cmd, arg0, arg1, data)
    }
}

private class UsbTransport(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface
) {
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private var buffer = ByteArray(0)

    init {
        conn.claimInterface(iface, true)
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        epIn = inEp ?: throw IOException("no bulk IN endpoint")
        epOut = outEp ?: throw IOException("no bulk OUT endpoint")
    }

    fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = data.copyOfRange(off, minOf(data.size, off + MAX_WRITE_CHUNK))
            val n = conn.bulkTransfer(epOut, chunk, chunk.size, WRITE_TIMEOUT)
            if (n < 0) throw IOException("USB write failed")
            off += chunk.size
        }
    }

    fun read(len: Int, timeout: Int): ByteArray {
        while (buffer.size < len) {
            val tmp = ByteArray(READ_CHUNK)
            val n = conn.bulkTransfer(epIn, tmp, tmp.size, timeout)
            if (n < 0) throw IOException("USB read failed or timed out")
            buffer += tmp.copyOf(n)
        }
        val out = buffer.copyOf(len)
        buffer = buffer.copyOfRange(len, buffer.size)
        return out
    }

    fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }
}

private class AdbKeys(private val privateKey: PrivateKey, private val publicKey: PublicKey) {

    // חתימת האסימון של AUTH (RSA PKCS#1 v1.5 עם SHA-1)
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    // המפתח הציבורי בפורמט ש-ADB מצפה לו, לשליחה כשהמכשיר לא מקבל את החתימה
    fun publicKeyAdb(): ByteArray {
        val pub = publicKey as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64).putInt(n0inv)
        buf.put(leBytes(n, 256))
        buf.put(leBytes(rr, 256))
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 adbtool\u0000".toByteArray()
    }

    companion object {
        private const val PREFS = "adb_keys"

        // טוען מפתח שמור, או יוצר אחד חדש בפעם הראשונה. המכשיר זוכר את המפתח אחרי האישור
        fun load(context: Context): AdbKeys {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val kf = KeyFactory.getInstance("RSA")
            val savedPriv = prefs.getString("priv", null)
            val savedPub = prefs.getString("pub", null)
            if (savedPriv != null && savedPub != null) {
                return AdbKeys(
                    kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(savedPriv, Base64.NO_WRAP))),
                    kf.generatePublic(X509EncodedKeySpec(Base64.decode(savedPub, Base64.NO_WRAP)))
                )
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val pair = gen.generateKeyPair()
            prefs.edit()
                .putString("priv", Base64.encodeToString(pair.getPrivate().encoded, Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(pair.getPublic().encoded, Base64.NO_WRAP))
                .apply()
            return AdbKeys(pair.getPrivate(), pair.getPublic())
        }
    }
}

// המרת מספר גדול לבייטים בסדר little-endian באורך קבוע
private fun leBytes(x: BigInteger, size: Int): ByteArray {
    val be = x.toByteArray()
    val out = ByteArray(size)
    for (i in 0 until size) {
        val idx = be.size - 1 - i
        if (idx >= 0) out[i] = be[idx]
    }
    return out
}
