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
import java.text.DecimalFormat




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
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothManager: BluetoothManager
    private val handler = Handler()

    @Volatile
    var stopWorker = false
    private var value = ""
    private val connectionless: Connectionless = Connectionless()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initBluetooth()



        binding.btnSelect.setOnClickListener {
            checkPermission()
            btScan()
        }

        binding.btnPrint.setOnClickListener {
            if (btPermission) {
                printInvoice()
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
                btScan()
            }
        }
    }

    private val btActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            btScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun btScan() {
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
            val bluetoothDevicesAdapter  =

                SimpleAdapter(
                    this@MainActivity,
                    devicesList,
                    R.layout.item_list,
                    fromHere,
                    viewsHere
                )

            btnList.adapter = bluetoothDevicesAdapter
            btnList.onItemClickListener =
                AdapterView.OnItemClickListener { _, _, position, _ ->
                    val selectedDevice = devicesList[position]
                    val printName = selectedDevice["A"].toString()
                    binding.etBluetoothPrinter.setText(printName)
                    connectionless.printerName = printName
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
                                        readBuffer, 0,
                                        encodedBytes, 0,
                                        encodedBytes.size
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
        val printerName = connectionless.printerName
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
                            socket = connectToAntherDevice.invoke(bluetoothDevice, 1) as BluetoothSocket
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












    private fun printInvoice() {
        try {
            var str: String
            val invoiceHeader = "Tax Invoice"
            val address = "Deccon Tech"
            val customerName = "Self"
            val cashierName = "Admin"
            val companyName = "Thermal Printer Sample"
            val mobileNumber = "98000000000"
            val gstin = "Gst no"
            val billNumber = "1001"
            val billDate = "06-09-2023"
            val tableNumber = "5"
            val thankYouMessage = "Thanks"
            val amountInWords = "One Hundred five Only"
            val totalAmount = 100.00
            val gstAmount = 5.0
            val grandTotal = 105.00


            val textData = StringBuilder()
            val itemDetails = StringBuilder()
            val totalsData = StringBuilder()
            val wordsData = StringBuilder()
            val footerData = StringBuilder()


            if (invoiceHeader.isNotEmpty()) {
                textData.append("$invoiceHeader\n")
            }
            textData.append("$companyName\n")
            textData.append("$address\n")
            if (mobileNumber.isNotEmpty()) {
                textData.append("$mobileNumber\n")
            }
            if (gstin.isNotEmpty()) {
                textData.append("$gstin\n")
            }

            str = String.format("%-14s %17s", "Inv#$billNumber", "Table#: $tableNumber")
            textData.append("$str\n")
            textData.append("Data Time: $billDate\n")

            itemDetails.append("--------------------------------------\n")
            itemDetails.append("Item Description\n")

            str = String.format("%-11s %9s %18s", "Qty", "Rate", "Amount")
            itemDetails.append("$str\n")
            itemDetails.append("--------------------------------------\n")

            val decimalFormat = DecimalFormat("0.00")
            var itemName: String
            var rate: String?
            var quantity: String
            var itemAmount: String?

            for (i in 0 until 10) {
                val price = 10
                itemName = "Item $i"
                rate = decimalFormat.format(price)
                quantity = "1 pc"
                itemAmount = "10.0"
                itemDetails.append("$itemName\n")
                str = String.format("%-11s %9s %10s", quantity, rate, itemAmount)
                itemDetails.append("$str\n")
            }
            itemDetails.append("--------------------------------------\n")

            str = String.format("%-9s %-11s %10s", customerName, "Total:", totalAmount)
            itemDetails.append("$str\n")
            str = String.format("%-9s %-11s %10s", cashierName, "Gst:", gstAmount)
            itemDetails.append("$str\n")
            str = String.format("%-7s %8s", "Total:", grandTotal)
            totalsData.append("$str\n")

            wordsData.append("$amountInWords\n")
            if (thankYouMessage.isNotEmpty()) {
                footerData.append("$thankYouMessage\n")
            }
            footerData.append("Android App\n\n\n\n")

            intentPrint(
                textData.toString(),
                itemDetails.toString(),
                totalsData.toString(),
                wordsData.toString(),
                footerData.toString()
            )

            Log.d("printerAsh", "textData: $textData")
            Log.d("printerAsh", "itemDetails: $itemDetails")
            Log.d("printerAsh", "totalsData: $totalsData")
            Log.d("printerAsh", "wordsData: $wordsData")
            Log.d("printerAsh", "footerData: $footerData")

        } catch (ex: Exception) {
            Log.e("printerAsh", "Exception during printing", ex)
            Toast.makeText(this, "Exception during printing: ${ex.message}", Toast.LENGTH_LONG)
                .show()
        }
    }


    private fun intentPrint(
        txtvalue: String,
        txtvalue1: String,
        txtvalue2: String,
        txtvalue3: String,
        txtvalue4: String
    ) {

        if (connectionless.printerName.trim().isNotEmpty()) {
            val buffer = txtvalue1.toByteArray()
            val PrintHeader = byteArrayOf(0xAA.toByte(), 0x55, 2, 8)
            PrintHeader[3] = buffer.size.toByte()
            initPrinter()
            if (PrintHeader.size > 128) {
                value += "\nValue is more than 128 size\n"
                Toast.makeText(this, value, Toast.LENGTH_LONG).show()
                Log.d("printerAsh", value)
            } else {
                try {
                    if (socket != null) {
                        try {
                            val SP = byteArrayOf(0x18, 0x40)
                            outputStream!!.write(SP)
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                        val FONT_1X = byteArrayOf(0x18, 0x21, 0x00)
                        outputStream!!.write(FONT_1X)
                        val ALIGN_CENTER = byteArrayOf(0x18, 0x61, 1)
                        outputStream!!.write(ALIGN_CENTER)
                        outputStream!!.write(txtvalue.toByteArray())
                        val ALIGN_LEFT = byteArrayOf(0x18, 0x61, 0)
                        outputStream!!.write(ALIGN_LEFT)
                        outputStream!!.write(txtvalue1.toByteArray())
                        val FONT_2X = byteArrayOf(0x1B, 0x21, 0x30)
                        outputStream!!.write(FONT_2X)
                        outputStream!!.write(txtvalue2.toByteArray())
                        outputStream!!.write(FONT_1X)
                        outputStream!!.write(ALIGN_LEFT)
                        outputStream!!.write(txtvalue3.toByteArray())
                        outputStream!!.write(ALIGN_CENTER)
                        outputStream!!.write(txtvalue4.toByteArray())
                        val FEED_PAPER = byteArrayOf(0x1D, 0x56, 66, 0x00)
                        outputStream!!.write(FEED_PAPER)
                        outputStream!!.flush()
                        outputStream!!.close()
                        socket!!.close()


                    }
                } catch (ex: java.lang.Exception) {
                    Log.e("printerAsh", "Exception during printing", ex)
                    Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show()
                    Log.d("printerAsh", ex.message.toString())
                }
            }

        }
    }
}
