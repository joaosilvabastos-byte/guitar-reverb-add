package com.bastos.guitarreverbadd

import android.app.AlertDialog
import android.widget.EditText
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.WebResourceRequest

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), PurchasesUpdatedListener {

    private lateinit var webView: WebView
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private val FILECHOOSER_RESULTCODE = 100

    // Google Play Billing
    private lateinit var billingClient: BillingClient
    private var productDetailsList: List<ProductDetails> = emptyList()
    private val PRODUCT_ID_PRO = "pro_subscription"
    private val BASE_PLAN_ID = "pro-annual"
    private val TAG_BILLING = "GuitarReverbBilling"
    private var billingReconnectAttempts = 0
    private val MAX_BILLING_RECONNECT_ATTEMPTS = 3

    // Guarda notificações do Billing que cheguem ANTES da página acabar de carregar
    private var webViewPageLoaded = false
    private var pendingProNotify = false
    private var pendingPriceNotify: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("market://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webViewPageLoaded = true
                if (pendingProNotify) {
                    pendingProNotify = false
                    notificarWebViewProAtivo()
                }
                pendingPriceNotify?.let {
                    pendingPriceNotify = null
                    notificarWebViewPreco(it)
                }
            }
        }

        // 1. Ponte para downloads
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun downloadFile(base64DataUrl: String, fileName: String, mimeType: String) {
                runOnUiThread {
                    saveBlobToDownloads(base64DataUrl, fileName, mimeType)
                }
            }
        }, "AndroidDownloadBridge")

        // 2. Ponte para o Botão PRO
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun launchBillingFlow() {
                runOnUiThread {
                    iniciarFluxoCompra()
                }
            }
        }, "AndroidInterface")

        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            if (url.startsWith("data:")) {
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    saveBlobToDownloads(url, fileName, mimetype)
                } catch (e: Exception) {
                    Toast.makeText(this, "Erro no download: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessage?.onReceiveValue(null)
                uploadMessage = filePathCallback

                var intent: Intent? = null
                try {
                    intent = fileChooserParams?.createIntent()
                } catch (e: Exception) {
                    Log.e(TAG_BILLING, "Erro ao criar intent do FileChooser: ${e.localizedMessage}")
                }

                if (intent == null) {
                    intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                    }
                }

                return try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE)
                    true
                } catch (e: Exception) {
                    Log.e(TAG_BILLING, "Falha ao iniciar atividade de seleção de ficheiro: ${e.localizedMessage}")
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    false
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Guitar Reverb Add")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Guitar Reverb Add")
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        // Inicializa o Google Play Billing
        configurarBillingClient()
    }

    private fun configurarBillingClient() {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    billingReconnectAttempts = 0
                    consultarProdutos()
                    verificarComprasExistentes()
                } else {
                    Log.e(TAG_BILLING, "Falha ao ligar ao Billing: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG_BILLING, "BillingClient desligado. A tentar reconectar...")
                if (billingReconnectAttempts < MAX_BILLING_RECONNECT_ATTEMPTS) {
                    billingReconnectAttempts++
                    configurarBillingClient()
                }
            }
        })
    }

    private fun consultarProdutos() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params, object : ProductDetailsResponseListener {
            override fun onProductDetailsResponse(billingResult: BillingResult, result: QueryProductDetailsResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetailsList = result.productDetailsList
                    val proDetails = productDetailsList.find { it.productId == PRODUCT_ID_PRO }
                    if (proDetails != null) {
                        val offer = proDetails.subscriptionOfferDetails?.find { it.basePlanId == BASE_PLAN_ID }
                            ?: proDetails.subscriptionOfferDetails?.firstOrNull()

                        val formattedPrice = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                        if (formattedPrice != null) {
                            notificarWebViewPreco(formattedPrice)
                        } else {
                            Log.w(TAG_BILLING, "Preço não encontrado para a oferta do produto '$PRODUCT_ID_PRO'.")
                        }
                    } else {
                        Log.w(TAG_BILLING, "Produto '$PRODUCT_ID_PRO' não encontrado na Play Console.")
                    }
                } else {
                    Log.e(TAG_BILLING, "Erro ao consultar produtos: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                }
            }
        })
    }

    private fun iniciarFluxoCompra() {
        val productDetails = productDetailsList.find { it.productId == PRODUCT_ID_PRO }
        if (productDetails != null) {
            val offer = productDetails.subscriptionOfferDetails?.find { it.basePlanId == BASE_PLAN_ID }
                ?: productDetails.subscriptionOfferDetails?.firstOrNull()

            if (offer == null) {
                Toast.makeText(this, "Oferta de subscrição indisponível.", Toast.LENGTH_SHORT).show()
                return
            }

            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offer.offerToken)
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            val result = billingClient.launchBillingFlow(this, billingFlowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG_BILLING, "Falha ao abrir o fluxo de compra: ${result.responseCode} - ${result.debugMessage}")
                Toast.makeText(this, "Não foi possível abrir a compra. Tenta novamente.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "A carregar informação da loja, tenta novamente em instantes.", Toast.LENGTH_SHORT).show()
            consultarProdutos()
        }
    }

    private fun notificarWebViewPreco(formattedPrice: String) {
        if (!webViewPageLoaded) {
            pendingPriceNotify = formattedPrice
            return
        }
        runOnUiThread {
            val script = "javascript:if(typeof setProPrice === 'function') { setProPrice(${JSONObject.quote(formattedPrice)}); }"
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase -> processarCompra(purchase) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG_BILLING, "Compra cancelada pelo utilizador.")
            }
            else -> {
                Log.e(TAG_BILLING, "Erro na compra: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                Toast.makeText(this, "Não foi possível concluir a compra.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processarCompra(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val isProProduct = purchase.products.contains(PRODUCT_ID_PRO)
            if (isProProduct) {
                if (!purchase.isAcknowledged) {
                    val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { result ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            notificarWebViewProAtivo()
                        } else {
                            Log.e(TAG_BILLING, "Erro ao reconhecer subscrição: ${result.responseCode}")
                        }
                    }
                } else {
                    notificarWebViewProAtivo()
                }
            }
        }
    }

    private fun verificarComprasExistentes() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params, object : PurchasesResponseListener {
            override fun onQueryPurchasesResponse(billingResult: BillingResult, purchases: List<Purchase>) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    var foundActivePro = false
                    purchases.forEach { purchase ->
                        val isProProduct = purchase.products.contains(PRODUCT_ID_PRO)
                        if (isProProduct && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            foundActivePro = true
                            processarCompra(purchase)
                        }
                    }
                    if (!foundActivePro) {
                        runOnUiThread {
                            webView.evaluateJavascript("javascript:if(typeof setProStatus === 'function') { setProStatus(false); }", null)
                        }
                    }
                } else {
                    Log.e(TAG_BILLING, "Erro ao verificar compras existentes: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                }
            }
        })
    }

    private fun notificarWebViewProAtivo() {
        if (!webViewPageLoaded) {
            pendingProNotify = true
            return
        }
        runOnUiThread {
            webView.evaluateJavascript("javascript:if(typeof setProStatus === 'function') { setProStatus(true); }", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILECHOOSER_RESULTCODE) {
            val results = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            uploadMessage?.onReceiveValue(results)
            uploadMessage = null
        }
    }

    fun saveBlobToDownloads(base64DataUrl: String, fileName: String, mimetype: String) {
        try {
            val base64Data = if (base64DataUrl.contains(",")) {
                base64DataUrl.substring(base64DataUrl.indexOf(",") + 1)
            } else {
                base64DataUrl
            }
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimetype)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(decodedBytes)
                    }
                    Toast.makeText(this, "Ficheiro guardado emDownloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val file = File(downloadDir, fileName)
                FileOutputStream(file).use { os ->
                    os.write(decodedBytes)
                }
                Toast.makeText(this, "Ficheiro guardado em Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro no download: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
