package com.example.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.AdapterView
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.printer.databinding.ActivityMainBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var btPermission = false
    private var socket: BluetoothSocket? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var workerThread: Thread? = null
    private lateinit var readBuffer: ByteArray
    private var readBufferPosition = 0
    private val items = arrayListOf<Item>()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothManager: BluetoothManager
    private val handler = Handler()
    private var printerName = ""

    @Volatile
    var stopWorker = false
    private var value = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initBluetooth()



        binding.btnSelect.setOnClickListener {
            checkPermission()
            startBluetoothScanning()
        }

        binding.btnPrint.setOnClickListener {
            if (btPermission) {
                printInvoice(items)
            } else {
                checkPermission()

            }
        }
    }

    private fun initBluetooth() {
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_ADMIN)
        }
    }


    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            btPermission = true
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                btActivityResultLauncher.launch(enableBtIntent)
            } else {
                startBluetoothScanning()
            }
        }
    }

    private val btActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBluetoothScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScanning() {
        val builder = AlertDialog.Builder(this@MainActivity)
        val dialogView = layoutInflater.inflate(R.layout.scan_btn, null)
        builder.setCancelable(false)
        builder.setView(dialogView)
        val btnList = dialogView.findViewById<ListView>(R.id.btnList)
        val dialog = builder.create()

        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val devicesList: MutableList<Map<String, Any>> = mutableListOf()

        if (!pairedDevices.isNullOrEmpty()) {
            for (device in pairedDevices) {
                val deviceMap: MutableMap<String, Any> = mutableMapOf()
                deviceMap["A"] = device.name
                deviceMap["B"] = device.address
                devicesList.add(deviceMap)
            }

            val fromHere = arrayOf("A")
            val viewsHere = intArrayOf(R.id.itemName)
            val bluetoothDevicesAdapter =

                SimpleAdapter(
                    this@MainActivity, devicesList, R.layout.item_list, fromHere, viewsHere
                )

            btnList.adapter = bluetoothDevicesAdapter
            btnList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val selectedDevice = devicesList[position]
                val printName = selectedDevice["A"].toString()
                binding.etBluetoothPrinter.setText(printName)
                printerName = printName
//                connectionless.printerName = printName
                dialog.dismiss()
            }
            dialog.show()
        } else {
            val value = "No Paired Devices Found"
            Toast.makeText(this, value, Toast.LENGTH_LONG).show()
            Log.d("printer", value)
        }
    }

    private fun beginListenForData() {
        try {

            val delimiter: Byte = 10
            stopWorker = false
            readBufferPosition = 0
            readBuffer = ByteArray(1024)
            workerThread = Thread {
                while (!Thread.currentThread().isInterrupted && !stopWorker) {
                    try {
                        val bytesAvailable = inputStream!!.available()
                        if (bytesAvailable > 0) {
                            val packageByte = ByteArray(bytesAvailable)
                            inputStream!!.read(packageByte)
                            for (i in 0 until bytesAvailable) {
                                val byte = packageByte[i]
                                if (byte == delimiter) {
                                    val encodedBytes = ByteArray(readBufferPosition)
                                    System.arraycopy(
                                        readBuffer, 0, encodedBytes, 0, encodedBytes.size
                                    )

                                    val data = String(encodedBytes, Charset.forName("US-ASCII"))
                                    readBufferPosition = 0

                                    handler.post { Log.d("printer", data) }
                                } else {
                                    readBuffer[readBufferPosition++] = byte
                                }
                            }
                        }

                    } catch (ex: IOException) {
                        stopWorker = true
                    }
                }
            }
            workerThread!!.start()

        } catch (e: java.lang.Exception) {
            e.printStackTrace()

        }
    }

    @SuppressLint("MissingPermission")
    fun initPrinter() {
//        val printerName = connectionless.printerName
        try {
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                btActivityResultLauncher.launch(enableBtIntent)
            }
            val pairDevice = bluetoothAdapter.bondedDevices
            if (pairDevice != null) {
                if (pairDevice.size > 0) {
                    for (device in pairDevice) {
                        if (device.name == printerName) {
                            bluetoothDevice = device
                            val connectToAntherDevice = bluetoothDevice!!.javaClass.getMethod(
                                "createRfcommSocket", *arrayOf<Class<*>?>(
                                    Int::class.javaPrimitiveType
                                )
                            )
                            socket =
                                connectToAntherDevice.invoke(bluetoothDevice, 1) as BluetoothSocket
                            bluetoothAdapter.cancelDiscovery()
                            socket!!.connect()
                            outputStream = socket!!.outputStream
                            inputStream = socket!!.inputStream
                            beginListenForData()
                            break
                        }
                    }
                } else {
                    value = "No Device Found"
                    Toast.makeText(this, value, Toast.LENGTH_LONG).show()
                    return
                }
            }


        } catch (ee: java.lang.Exception) {
            Toast.makeText(this, "Bluetooth Printer Not Connected", Toast.LENGTH_LONG).show()
            socket = null
        }

    }

    private fun printInvoice(
        items: ArrayList<Item>
    ) {
        items.add(Item("منتج 1", 2, 20.0))
        items.add(Item("منتج 2", 5, 30.0))
        val companyName = "\u202Eاسم الفاتوره"
        val address = "\u202Eعنوان "
        val mobileNumber = "\u202E1123456"
        val gstin = "\u202E5"
        val billNumber = "\u202E101 "
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale("ar"))
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("ar"))
        val billDate = "\u202E" + dateFormat.format(Date())
        val billTime = "\u202E" + timeFormat.format(Date())

        val totalAmount = 100.0
        val gstAmount = 10.0
        val grandTotal = 110.0
        val thankYouMessage = "\u202Eشكرًا لك"
        try {

            val finalInvoiceText = buildString {
                append("$companyName\n$address\nرقم الجوال: $mobileNumber\nرقم الضريبة: $gstin\n")
                append("رقم الفاتورة: $billNumber\n")
                append("تاريخ الفاتورة: $billDate\n")
                append("وقت الفاتورة: $billTime\n")
                appendLine("------------------------------------------------")
                append("\u202Eالعنصر\tالكمية\tالسعر\n")
                appendLine("------------------------------------------------")
                items.forEach { item ->
                    val itemTotal = item.quantity.toDouble() * item.price
                    append("\u202E${item.name}\t${item.quantity}\t${item.price}\n")
                }
                append("اجمالي الحساب قبل الضريبة: $totalAmount\n")
                append("ضريبة: $gstAmount\n")
                append("الإجمالي بعد الضريبة: $grandTotal\n")
                append("------------------------------------------------\n")
                append("$thankYouMessage\n")
            }

            sendDataTPrinter(finalInvoiceText)
            Log.d("printer", finalInvoiceText)
        } catch (ex: Exception) {
            Log.e("printerError", "خطأ أثناء الطباعة", ex)
            Toast.makeText(this, "خطأ أثناء الطباعة: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun sendDataTPrinter(data: String) {
        if (printerName.trim().isNotEmpty()) {
            Log.d("printer", "Printer Name: $printerName")
            val buffer = data.toByteArray()
            val printHeader = byteArrayOf(0xAA.toByte(), 0x55, 2, 8)
            printHeader[3] = buffer.size.toByte()
            initPrinter()
            if (printHeader.size > 128) {
                Log.d("printerAsh", "Value is more than 128 size")
            } else {
                try {
                    if (socket != null) {
                        val SP = byteArrayOf(0x18, 0x40)
                        outputStream!!.write(SP)
                        Thread.sleep(1000)
                        val FONT_1X = byteArrayOf(0x18, 0x21, 0x00)
                        outputStream!!.write(FONT_1X)
                        val ALIGN_CENTER = byteArrayOf(0x18, 0x61, 1)
                        outputStream!!.write(ALIGN_CENTER)
                        outputStream!!.write(data.toByteArray())
                        val ALIGN_LEFT = byteArrayOf(0x18, 0x61, 0)
                        outputStream!!.write(ALIGN_LEFT)
                        val FONT_2X = byteArrayOf(0x1B, 0x21, 0x30)
                        outputStream!!.write(FONT_2X)
                        outputStream!!.write(FONT_1X)
                        outputStream!!.write(ALIGN_LEFT)
                        outputStream!!.write(ALIGN_CENTER)
                        val FEED_PAPER = byteArrayOf(0x1D, 0x56, 66, 0x00)
                        outputStream!!.write(FEED_PAPER)
                        outputStream!!.flush()
                        outputStream!!.close()
                        socket!!.close()
                    }
                } catch (ex: java.lang.Exception) {
                    Log.e("printerAsh", "Exception during printing", ex)
                }
            }
        } else {
            Toast.makeText(this, "الرجاء تحديد الطابعة", Toast.LENGTH_LONG).show()
        }
    }

}
