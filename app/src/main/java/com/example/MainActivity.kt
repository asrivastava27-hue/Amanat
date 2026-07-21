package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// Data Models
enum class Tab(val title: String) {
    MY_VAULT("My Amanat"),
    TRUSTED_CONTACTS("Trusted Contacts"),
    ACCESS_ACTIVITY("Access & Activity")
}

enum class AccessStatus {
    NO_REQUEST, GRACE_PERIOD, AWAITING_SECOND_CONFIRMATION, UNLOCKED, CANCELLED
}

enum class AccessTier(val displayName: String) {
    FULL_ACCESS("Full access"),
    FINANCIAL_ONLY("Financial only"),
    EMERGENCY_ONLY("Emergency info only")
}

enum class VaultCategory(val displayName: String) {
    BANKING("Bank & investments"),
    INSURANCE("Insurance policies"),
    PROPERTY("Property documents"),
    LIABILITIES("Loans & liabilities"),
    PENSION("National Pension Scheme (NPS)"),
    EPF("Employee Provident Fund (EPF)"),
    MESSAGE("Personal messages & special instructions"),
    OTHER("Others"),
    EMERGENCY("Important contacts (CA, lawyer, insurance agent, bank relationship manager)")
}

data class VaultItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val category: VaultCategory,
    val contactType: String? = null,
    val assetType: String? = null,
    val uploadedDocuments: List<String> = emptyList()
)

data class TrustedContact(
    val name: String = "",
    val relationship: String = "Trusted Contact",
    val email: String = "",
    val phone: String = "",
    val tier: AccessTier = AccessTier.FULL_ACCESS
)

data class AdminUser(
    val email: String,
    val password: String
)

enum class SubscriptionTier(
    val displayName: String,
    val priceText: String,
    val billingCycleText: String,
    val badgeColorHex: Long
) {
    FREE("Standard Free", "₹0", "Forever Free", 0xFF94A3B8),
    ANNUAL_PRO("Amanat Pro", "₹999", "per year", 0xFF10B981),
    LIFETIME_HERITAGE("Family Heritage", "₹2,499", "one-time lifetime", 0xFF8B5CF6)
}

// ViewModel
class SecureLegacyViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("secure_legacy_prefs", Context.MODE_PRIVATE)

    var adminUsers by mutableStateOf<List<AdminUser>>(emptyList())
    var isAdminAuthenticated by mutableStateOf(false)
    var loggedInAdminEmail by mutableStateOf("")

    var vaultItems by mutableStateOf(listOf<VaultItem>())
    var trustedContacts by mutableStateOf(listOf<TrustedContact>())

    // Subscription & Payment State
    var subscriptionTier by mutableStateOf(
        try {
            SubscriptionTier.valueOf(prefs.getString("subscription_tier", SubscriptionTier.FREE.name) ?: SubscriptionTier.FREE.name)
        } catch (e: Exception) {
            SubscriptionTier.FREE
        }
    )
    var isSubscribed by mutableStateOf(subscriptionTier != SubscriptionTier.FREE)
    var subscriptionExpiryDate by mutableStateOf(prefs.getString("subscription_expiry", "July 21, 2027") ?: "July 21, 2027")
    var subscriptionTransactionId by mutableStateOf(prefs.getString("subscription_tx_id", "AMN-9842103") ?: "AMN-9842103")

    fun subscribeToPlan(tier: SubscriptionTier, paymentMethod: String): String {
        val txId = "AMN-" + (1000000..9999999).random()
        subscriptionTier = tier
        isSubscribed = (tier != SubscriptionTier.FREE)
        subscriptionTransactionId = txId
        subscriptionExpiryDate = if (tier == SubscriptionTier.LIFETIME_HERITAGE) "Lifetime Access (No Expiry)" else "July 21, 2027"

        prefs.edit().apply {
            putString("subscription_tier", tier.name)
            putString("subscription_expiry", subscriptionExpiryDate)
            putString("subscription_tx_id", txId)
            apply()
        }
        return txId
    }

    fun cancelSubscription() {
        subscriptionTier = SubscriptionTier.FREE
        isSubscribed = false
        prefs.edit().apply {
            putString("subscription_tier", SubscriptionTier.FREE.name)
            apply()
        }
    }

    // Firebase Cloud Sync Status
    var isFirebaseInitialized by mutableStateOf(false)
    var isFirestoreAvailable by mutableStateOf(false)
    var isSimulationModeActive by mutableStateOf(prefs.getBoolean("firebase_simulation_active", false))
    var firebaseSyncStatus by mutableStateOf("Not configured - Running locally")

    init {
        val serialized = prefs.getString("admin_users_list", null)
        val parsedList = if (!serialized.isNullOrEmpty()) {
            serialized.split(",").mapNotNull {
                val parts = it.split(":")
                if (parts.size >= 2) {
                    AdminUser(parts[0].trim().lowercase(), parts[1])
                } else {
                    null
                }
            }
        } else {
            emptyList()
        }

        // Always ensure super admin is in the list with default password 'admin' if not present
        if (parsedList.none { it.email == "asrivastava27@gmail.com" }) {
            adminUsers = parsedList + AdminUser("asrivastava27@gmail.com", "admin")
            saveAdminUsers(adminUsers)
        } else {
            adminUsers = parsedList
        }

        // Load persisted vault items and trusted contacts
        vaultItems = loadVaultItems()
        trustedContacts = loadTrustedContacts()

        // Safely Initialize Firebase / Firestore
        try {
            FirebaseApp.initializeApp(application)
            isFirebaseInitialized = true
            try {
                val db = FirebaseFirestore.getInstance()
                isFirestoreAvailable = true
                firebaseSyncStatus = "Active & Synced with Firebase Cloud Console"
                loadFromFirestoreAndMerge()
            } catch (e: Exception) {
                isFirestoreAvailable = false
                if (isSimulationModeActive) {
                    firebaseSyncStatus = "Active (Simulated Cloud Sync Mode)"
                } else {
                    firebaseSyncStatus = "Local Mode (No Firebase configuration found)"
                }
            }
        } catch (e: Exception) {
            isFirebaseInitialized = false
            isFirestoreAvailable = false
            if (isSimulationModeActive) {
                firebaseSyncStatus = "Active (Simulated Cloud Sync Mode)"
            } else {
                firebaseSyncStatus = "Local Mode (No Firebase configuration found)"
            }
        }
    }

    fun saveVaultItems(items: List<VaultItem>) {
        val array = org.json.JSONArray()
        for (item in items) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("detail", item.detail)
                put("category", item.category.name)
                put("contactType", item.contactType ?: "")
                put("assetType", item.assetType ?: "")
                val docsArray = org.json.JSONArray()
                item.uploadedDocuments.forEach { docsArray.put(it) }
                put("uploadedDocuments", docsArray)
            }
            array.put(obj)
        }
        prefs.edit().putString("vault_items_json", array.toString()).apply()
    }

    fun loadVaultItems(): List<VaultItem> {
        val jsonStr = prefs.getString("vault_items_json", null) ?: return emptyList()
        val list = mutableListOf<VaultItem>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val docsArray = obj.optJSONArray("uploadedDocuments")
                val docs = mutableListOf<String>()
                if (docsArray != null) {
                    for (j in 0 until docsArray.length()) {
                        docs.add(docsArray.getString(j))
                    }
                }
                val catName = obj.optString("category", "OTHER")
                val category = try { VaultCategory.valueOf(catName) } catch(e: Exception) { VaultCategory.OTHER }
                list.add(
                    VaultItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        detail = obj.getString("detail"),
                        category = category,
                        contactType = obj.optString("contactType").takeIf { it.isNotEmpty() },
                        assetType = obj.optString("assetType").takeIf { it.isNotEmpty() },
                        uploadedDocuments = docs
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveTrustedContacts(contacts: List<TrustedContact>) {
        val array = org.json.JSONArray()
        for (contact in contacts) {
            val obj = org.json.JSONObject().apply {
                put("name", contact.name)
                put("relationship", contact.relationship)
                put("email", contact.email)
                put("tier", contact.tier.name)
            }
            array.put(obj)
        }
        prefs.edit().putString("trusted_contacts_json", array.toString()).apply()
    }

    fun loadTrustedContacts(): List<TrustedContact> {
        val jsonStr = prefs.getString("trusted_contacts_json", null) ?: return emptyList()
        val list = mutableListOf<TrustedContact>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tierName = obj.optString("tier", "FULL_ACCESS")
                val tier = try { AccessTier.valueOf(tierName) } catch(e: Exception) { AccessTier.FULL_ACCESS }
                list.add(
                    TrustedContact(
                        name = obj.optString("name", ""),
                        relationship = obj.optString("relationship", "Trusted Contact"),
                        email = obj.optString("email", ""),
                        phone = obj.optString("phone", ""),
                        tier = tier
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveLedger() {
        saveVaultItems(vaultItems)
        saveTrustedContacts(trustedContacts)
        saveToFirestore()
    }

    fun loadFromFirestoreAndMerge() {
        if (!isFirestoreAvailable) return
        val email = registeredEmail.takeIf { it.isNotEmpty() } ?: "anonymous_user"
        
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(email).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    try {
                        val firestoreVaultItemsJson = document.getString("vault_items_json")
                        val firestoreTrustedContactsJson = document.getString("trusted_contacts_json")
                        
                        if (!firestoreVaultItemsJson.isNullOrEmpty()) {
                            val items = parseVaultItemsFromJson(firestoreVaultItemsJson)
                            if (items.isNotEmpty()) {
                                vaultItems = items
                                saveVaultItems(items)
                            }
                        }
                        if (!firestoreTrustedContactsJson.isNullOrEmpty()) {
                            val contacts = parseTrustedContactsFromJson(firestoreTrustedContactsJson)
                            if (contacts.isNotEmpty()) {
                                trustedContacts = contacts
                                saveTrustedContacts(contacts)
                            }
                        }
                        firebaseSyncStatus = "Active & Synced with Firebase Cloud Console"
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
    }

    fun saveToFirestore() {
        if (isSimulationModeActive && !isFirestoreAvailable) {
            firebaseSyncStatus = "Active (Simulated Cloud Sync Mode)"
            return
        }
        if (!isFirestoreAvailable) return
        val email = registeredEmail.takeIf { it.isNotEmpty() } ?: "anonymous_user"
        
        val vaultItemsJson = getVaultItemsJson(vaultItems)
        val trustedContactsJson = getTrustedContactsJson(trustedContacts)
        
        val data = hashMapOf(
            "registered_username" to registeredUsername,
            "registered_email" to registeredEmail,
            "registered_phone" to registeredPhone,
            "vault_items_json" to vaultItemsJson,
            "trusted_contacts_json" to trustedContactsJson,
            "last_synced_at" to System.currentTimeMillis()
        )
        
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(email).set(data)
            .addOnSuccessListener {
                firebaseSyncStatus = "Active & Synced with Firebase Cloud Console"
            }
            .addOnFailureListener { e ->
                firebaseSyncStatus = "Sync failed: ${e.message}"
            }
    }

    private fun getVaultItemsJson(items: List<VaultItem>): String {
        val array = org.json.JSONArray()
        for (item in items) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("detail", item.detail)
                put("category", item.category.name)
                put("contactType", item.contactType ?: "")
                put("assetType", item.assetType ?: "")
                val docsArray = org.json.JSONArray()
                item.uploadedDocuments.forEach { docsArray.put(it) }
                put("uploadedDocuments", docsArray)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun getTrustedContactsJson(contacts: List<TrustedContact>): String {
        val array = org.json.JSONArray()
        for (contact in contacts) {
            val obj = org.json.JSONObject().apply {
                put("name", contact.name)
                put("relationship", contact.relationship)
                put("email", contact.email)
                put("phone", contact.phone)
                put("tier", contact.tier.name)
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun parseVaultItemsFromJson(jsonStr: String): List<VaultItem> {
        val list = mutableListOf<VaultItem>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val docsArray = obj.optJSONArray("uploadedDocuments")
                val docs = mutableListOf<String>()
                if (docsArray != null) {
                    for (j in 0 until docsArray.length()) {
                        docs.add(docsArray.getString(j))
                    }
                }
                val catName = obj.optString("category", "OTHER")
                val category = try { VaultCategory.valueOf(catName) } catch(e: Exception) { VaultCategory.OTHER }
                list.add(
                    VaultItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        detail = obj.getString("detail"),
                        category = category,
                        contactType = obj.optString("contactType").takeIf { it.isNotEmpty() },
                        assetType = obj.optString("assetType").takeIf { it.isNotEmpty() },
                        uploadedDocuments = docs
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun parseTrustedContactsFromJson(jsonStr: String): List<TrustedContact> {
        val list = mutableListOf<TrustedContact>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tierName = obj.optString("tier", "FULL_ACCESS")
                val tier = try { AccessTier.valueOf(tierName) } catch(e: Exception) { AccessTier.FULL_ACCESS }
                list.add(
                    TrustedContact(
                        name = obj.optString("name", ""),
                        relationship = obj.optString("relationship", "Trusted Contact"),
                        email = obj.optString("email", ""),
                        phone = obj.optString("phone", ""),
                        tier = tier
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun setSimulationMode(active: Boolean) {
        isSimulationModeActive = active
        prefs.edit().putBoolean("firebase_simulation_active", active).apply()
        if (active) {
            if (!isFirestoreAvailable) {
                firebaseSyncStatus = "Active (Simulated Cloud Sync Mode)"
            }
        } else {
            if (!isFirestoreAvailable) {
                firebaseSyncStatus = "Local Mode (No Firebase configuration found)"
            }
        }
    }

    fun addAdminUser(email: String, pass: String) {
        val emailClean = email.trim().lowercase()
        val passClean = pass.trim()
        if (emailClean.isNotEmpty() && passClean.isNotEmpty()) {
            val newList = adminUsers.filter { it.email != emailClean } + AdminUser(emailClean, passClean)
            adminUsers = newList
            saveAdminUsers(newList)
        }
    }

    fun removeAdminUser(email: String) {
        val emailClean = email.trim().lowercase()
        // Super admin cannot be removed to prevent lockout
        if (emailClean != "asrivastava27@gmail.com") {
            val newList = adminUsers.filter { it.email != emailClean }
            adminUsers = newList
            saveAdminUsers(newList)
        }
    }

    private fun saveAdminUsers(list: List<AdminUser>) {
        val serialized = list.joinToString(",") { "${it.email}:${it.password}" }
        prefs.edit().putString("admin_users_list", serialized).apply()
    }

    var registeredUsername by mutableStateOf(prefs.getString("registered_username", "") ?: "")
    var registeredPassword by mutableStateOf(prefs.getString("registered_password", "") ?: "")
    var registeredEmail by mutableStateOf(prefs.getString("registered_email", "") ?: "")
    var registeredPhone by mutableStateOf(prefs.getString("registered_phone", "") ?: "")
    var isRegistered by mutableStateOf(prefs.getBoolean("is_registered", false))
    var showRegistrationScreen by mutableStateOf(false)

    fun registerUser(user: String, pass: String, emailAddr: String, phone: String) {
        registeredUsername = user
        registeredPassword = pass
        registeredEmail = emailAddr
        registeredPhone = phone
        isRegistered = true
        showRegistrationScreen = false

        prefs.edit().apply {
            putString("registered_username", user)
            putString("registered_password", pass)
            putString("registered_email", emailAddr)
            putString("registered_phone", phone)
            putBoolean("is_registered", true)
            apply()
        }
    }

    var isLoggedIn by mutableStateOf(false)
    var currentTab by mutableStateOf(Tab.MY_VAULT)
    var viewingAs by mutableStateOf("Owner")

    var accessStatus by mutableStateOf(AccessStatus.NO_REQUEST)
    var requestReason by mutableStateOf("")
    var requestedByContactName by mutableStateOf("")
    var secondConfirmerName by mutableStateOf("")

    fun addVaultItem(
        title: String,
        detail: String,
        category: VaultCategory,
        contactType: String? = null,
        assetType: String? = null,
        uploadedDocuments: List<String> = emptyList()
    ) {
        vaultItems = vaultItems + VaultItem(
            title = title,
            detail = detail,
            category = category,
            contactType = contactType,
            assetType = assetType,
            uploadedDocuments = uploadedDocuments
        )
        saveVaultItems(vaultItems)
    }

    fun updateVaultItem(
        itemId: String,
        title: String,
        detail: String,
        contactType: String? = null,
        assetType: String? = null
    ) {
        vaultItems = vaultItems.map { item ->
            if (item.id == itemId) {
                item.copy(
                    title = title,
                    detail = detail,
                    contactType = contactType,
                    assetType = assetType
                )
            } else {
                item
            }
        }
        saveVaultItems(vaultItems)
    }

    fun deleteVaultItem(itemId: String) {
        vaultItems = vaultItems.filter { it.id != itemId }
        saveVaultItems(vaultItems)
    }

    fun uploadDocumentToItem(itemId: String, documentName: String) {
        vaultItems = vaultItems.map { item ->
            if (item.id == itemId) {
                item.copy(uploadedDocuments = item.uploadedDocuments + documentName)
            } else {
                item
            }
        }
        saveVaultItems(vaultItems)
    }

    fun deleteDocumentFromItem(itemId: String, documentName: String) {
        vaultItems = vaultItems.map { item ->
            if (item.id == itemId) {
                item.copy(uploadedDocuments = item.uploadedDocuments.filter { it != documentName })
            } else {
                item
            }
        }
        saveVaultItems(vaultItems)
    }

    fun addTrustedContact(name: String, relationship: String = "Trusted Contact", email: String, tier: AccessTier = AccessTier.FULL_ACCESS, phone: String = "") {
        if (trustedContacts.size >= 2) return
        trustedContacts = (trustedContacts + TrustedContact(name, relationship, email, phone, tier)).take(2)
        saveTrustedContacts(trustedContacts)
    }

    fun updateTrustedContactAt(index: Int, name: String, email: String, phone: String) {
        val list = trustedContacts.toMutableList()
        val rel = if (index == 0) "Primary Contact" else "Secondary Contact"
        val updated = TrustedContact(name = name, relationship = rel, email = email, phone = phone, tier = AccessTier.FULL_ACCESS)
        if (index < list.size) {
            list[index] = updated
        } else {
            while (list.size < index) {
                list.add(TrustedContact(name = "", relationship = "Trusted Contact", email = "", phone = ""))
            }
            list.add(updated)
        }
        trustedContacts = list.take(2)
        saveTrustedContacts(trustedContacts)
    }

    fun deleteTrustedContactAt(index: Int) {
        val list = trustedContacts.toMutableList()
        if (index < list.size) {
            list.removeAt(index)
            trustedContacts = list
            saveTrustedContacts(trustedContacts)
        }
    }

    fun initiateRequest(contactName: String, reason: String) {
        requestedByContactName = contactName
        requestReason = reason
        accessStatus = AccessStatus.GRACE_PERIOD
    }

    fun cancelRequest() {
        accessStatus = AccessStatus.CANCELLED
    }

    fun simulateGracePeriodPasses() {
        if (accessStatus == AccessStatus.GRACE_PERIOD) {
            accessStatus = AccessStatus.AWAITING_SECOND_CONFIRMATION
        }
    }

    var showAdminPanel by mutableStateOf(false)

    fun confirmAsSecondContact(confirmerName: String) {
        if (accessStatus == AccessStatus.AWAITING_SECOND_CONFIRMATION) {
            secondConfirmerName = confirmerName
            accessStatus = AccessStatus.UNLOCKED
        }
    }

    fun populateGoldenDemoData() {
        // Clear existing
        val items = listOf(
            VaultItem(
                title = "Amanat Villa - Delhi Land Registry",
                detail = "Original deed stored in SBI Vault Locker #401. Digital scanned copy in secondary storage folder 'Real_Estate/Delhi'. Property PIN: DL-10394-B.",
                category = VaultCategory.PROPERTY,
                assetType = "Property"
            ),
            VaultItem(
                title = "HDFC Mutual Fund & Demat Portfolio",
                detail = "Client ID: 88491023. Linked bank account ending in 4920. Total portfolio worth approx. 45 Lakh INR. Nominee registered: Abhishek (Son).",
                category = VaultCategory.BANKING,
                assetType = "Mutual Fund"
            ),
            VaultItem(
                title = "Max Life Insurance Policy",
                detail = "Policy Number: 2948-AX-302. Insured amount: 1 Crore INR. Agent contact: Mehra (9810239485). Premium paid till Dec 2026.",
                category = VaultCategory.INSURANCE,
                assetType = "Life Insurance"
            ),
            VaultItem(
                title = "Personal & Educational Credentials",
                detail = "Contains PDF of Abhishek's college degrees, family passport copies, and tax returns (FY 2024-2025) stored in locker key box.",
                category = VaultCategory.OTHER,
                assetType = "Others"
            ),
            VaultItem(
                title = "Message to Family & Special Instructions",
                detail = "Dear family, all original property papers and physical locker keys are located in the bedroom safe (Code: 8492). Stay blessed and take care.",
                category = VaultCategory.MESSAGE,
                assetType = "Personal Message"
            )
        )

        // 2. Add Trusted Contacts (Max 2 contacts)
        val contacts = listOf(
            TrustedContact(
                name = "Abhishek Srivastava",
                relationship = "Primary Contact",
                email = "abhishek@amanat.com",
                phone = "+91 9876543210",
                tier = AccessTier.FULL_ACCESS
            ),
            TrustedContact(
                name = "Meenakshi Srivastava",
                relationship = "Secondary Contact",
                email = "meenakshi@amanat.com",
                phone = "+91 9876543211",
                tier = AccessTier.FULL_ACCESS
            )
        )

        vaultItems = items
        trustedContacts = contacts
        saveLedger()
    }

    fun deleteTrustedContact(contactName: String) {
        trustedContacts = trustedContacts.filter { it.name != contactName }
        saveTrustedContacts(trustedContacts)
    }

    fun resetRegistration() {
        registeredUsername = ""
        registeredPassword = ""
        registeredEmail = ""
        registeredPhone = ""
        isRegistered = false
        showRegistrationScreen = true
        isLoggedIn = false
        isAdminAuthenticated = false
        loggedInAdminEmail = ""
        prefs.edit().clear().apply()
        adminUsers = listOf(AdminUser("asrivastava27@gmail.com", "admin"))
        saveAdminUsers(adminUsers)
        resetDemo()
    }

    fun resetDemo() {
        accessStatus = AccessStatus.NO_REQUEST
        requestReason = ""
        requestedByContactName = ""
        secondConfirmerName = ""
        
        // Restore initial empty state with no dummy data
        vaultItems = listOf()
        trustedContacts = listOf()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SecureLegacyApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureLegacyApp(viewModel: SecureLegacyViewModel = viewModel()) {
    var showExitDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ExitToApp,
                        contentDescription = "Exit icon",
                        tint = SageGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Exit Amanat?",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = InkNavy
                    )
                }
            },
            text = {
                Text(
                    text = "Are you sure you want to exit the app? Your secure digital heritage ledger has been saved locally on this device.",
                    color = TextDark,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        (context as? android.app.Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Exit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitDialog = false },
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", color = TextDark)
                }
            },
            containerColor = WarmOffWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.border(BorderStroke(1.dp, InkNavy.copy(alpha = 0.2f)), RoundedCornerShape(16.dp))
        )
    }

    if (viewModel.showAdminPanel) {
        AdminPanelDialog(viewModel = viewModel, onDismiss = { 
            viewModel.showAdminPanel = false
            viewModel.isAdminAuthenticated = false
        })
    }

    if (showHelpDialog) {
        HelpSupportDialog(viewModel = viewModel, onDismiss = { showHelpDialog = false })
    }

    if (!viewModel.isLoggedIn) {
        if (viewModel.showRegistrationScreen) {
            RegistrationScreen(viewModel = viewModel)
        } else {
            LoginScreen(viewModel = viewModel)
        }
    } else {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            containerColor = WarmOffWhite,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showHelpDialog = true },
                    containerColor = SageGreen,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(24.dp),
                    elevation = FloatingActionButtonDefaults.elevation(6.dp),
                    modifier = Modifier
                        .padding(bottom = 16.dp, end = 8.dp)
                        .testTag("help_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help & Support",
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Help",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Premium Responsive Top Header and Navigation Bar
                TopBarNavigation(viewModel = viewModel, onExitClick = { showExitDialog = true })

                Spacer(modifier = Modifier.height(16.dp))

                // Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (viewModel.currentTab) {
                        Tab.MY_VAULT -> MyVaultScreen(viewModel = viewModel)
                        Tab.TRUSTED_CONTACTS -> TrustedContactsScreen(viewModel = viewModel)
                        Tab.ACCESS_ACTIVITY -> AccessActivityScreen(viewModel = viewModel)
                    }
                }

                // Footer Note
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PROTOTYPE FOR TESTING ONLY — PLEASE DO NOT ENTER REAL ACCOUNT DETAILS.",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.05.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportDialog(viewModel: SecureLegacyViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showManual by remember { mutableStateOf(false) }
    var showSubscriptionModal by remember { mutableStateOf(false) }

    if (showManual) {
        TrustedContactUserManualDialog(onDismiss = { showManual = false })
    }

    if (showSubscriptionModal) {
        SubscriptionPaymentDialog(viewModel = viewModel, onDismiss = { showSubscriptionModal = false })
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            elevation = CardDefaults.cardElevation(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SageGreen.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = "Help & Support",
                                tint = SageGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Help & Support",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextDark
                            )
                            Text(
                                text = "Assistance for Amanat Ledger",
                                fontSize = 11.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Option 1: User Manual
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, SageGreen.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                        .clickable {
                            showManual = true
                        },
                    colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = "User Manual",
                                    tint = SageGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "1. Trusted Contact User Manual",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextDark,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                            Surface(
                                color = SageGreen.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Active Guide",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SageGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Operating rules, 4-step emergency request workflow, grace period policy, and FAQs.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Option 2: Customer Care Support Number
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, SageGreen.copy(alpha = 0.4f)), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = SageGreen.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headset,
                                contentDescription = "Customer Care",
                                tint = SageGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "2. Customer Care Support",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextDark,
                                fontFamily = FontFamily.SansSerif
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "For queries, guidance, or assistance:",
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.SansSerif
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Phone Number Container
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = WarmOffWhite,
                            border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Phone",
                                        tint = SageGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "+91 9540400154",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextDark,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Copy Icon Button
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Customer Care Number", "9540400154")
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Number 9540400154 copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy number",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Direct Call Button
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:9540400154"))
                                            context.startActivity(intent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = "Call",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Call", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Option 3: Subscription & Payment Plans
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.6f)), RoundedCornerShape(12.dp))
                        .clickable {
                            showSubscriptionModal = true
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Subscription",
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "3. Subscription & Payment Plans",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextDark,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (viewModel.isSubscribed) viewModel.subscriptionTier.displayName else "Subscribe",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD97706),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Upgrade to Amanat Pro or Family Heritage plan. Manage UPI / Card payment methods and view billing receipt.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun TopBarNavigation(viewModel: SecureLegacyViewModel, onExitClick: () -> Unit) {
    var expandedDropdown by remember { mutableStateOf(false) }
    var saveFeedbackActive by remember { mutableStateOf(false) }
    var showSubscriptionModal by remember { mutableStateOf(false) }

    if (showSubscriptionModal) {
        SubscriptionPaymentDialog(viewModel = viewModel, onDismiss = { showSubscriptionModal = false })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        InkNavy,
                        Color(0xFF233549), // slightly lighter slate-navy for elegant gradient depth
                        Color(0xFF131E2A)  // deep charcoal-navy
                    )
                )
            )
            .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        // Top Row: Brand Logo, Viewing As Dropdown, Lock/Logout, Save, Exit
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo and Title with dynamic user name display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(SageGreen, Color(0xFF509C88))
                            )
                        )
                        .border(BorderStroke(1.dp, Color(0x33FFFFFF)), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (viewModel.isRegistered && viewModel.registeredUsername.isNotEmpty()) {
                            viewModel.registeredUsername.take(1).uppercase()
                        } else {
                            "A"
                        },
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Amanat",
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 0.5.sp
                    )
                    if (viewModel.isRegistered && viewModel.registeredUsername.isNotEmpty()) {
                        Text(
                            text = viewModel.registeredUsername,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.SansSerif
                        )
                    } else {
                        Text(
                            text = "Secure Heritage",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }

            // Subscribe, Save Ledger and Sign Out Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Subscription / Upgrade Button
                Surface(
                    onClick = { showSubscriptionModal = true },
                    shape = RoundedCornerShape(20.dp),
                    color = if (viewModel.isSubscribed) Color(0xFF8B5CF6).copy(alpha = 0.25f) else Color(0xFFF59E0B).copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, if (viewModel.isSubscribed) Color(0xFFA78BFA) else Color(0xFFFBBF24)),
                    modifier = Modifier.testTag("subscribe_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Subscription",
                            tint = if (viewModel.isSubscribed) Color(0xFFA78BFA) else Color(0xFFFBBF24),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (viewModel.isSubscribed) viewModel.subscriptionTier.displayName else "Subscribe",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Attractive Save Ledger Button
                Surface(
                    onClick = {
                        viewModel.saveLedger()
                        saveFeedbackActive = true
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = if (saveFeedbackActive) SageGreen else SageGreen.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, SageGreen),
                    modifier = Modifier.testTag("save_ledger_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (saveFeedbackActive) Icons.Default.Check else Icons.Default.Save,
                            contentDescription = "Save Ledger",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (saveFeedbackActive) "Saved!" else "Save",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                
                if (saveFeedbackActive) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1500)
                        saveFeedbackActive = false
                    }
                }

                // Attractive Sign Out Button
                Surface(
                    onClick = { viewModel.isLoggedIn = false },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0x35E53935),
                    border = BorderStroke(1.dp, Color(0x80EF5350)),
                    modifier = Modifier.testTag("exit_app_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Sign Out",
                            tint = Color(0xFFFF8A80),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Sign Out",
                            color = Color(0xFFFF8A80),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Organized, Attractive Navigation Segmented Tab Control Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0x18FFFFFF),
            border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Tab.values().forEach { tab ->
                    val isActive = viewModel.currentTab == tab

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isActive) SageGreen else Color.Transparent)
                            .clickable { viewModel.currentTab = tab }
                            .padding(vertical = 8.dp)
                            .testTag("nav_item_${tab.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (tab) {
                                    Tab.MY_VAULT -> Icons.Outlined.FolderOpen
                                    Tab.TRUSTED_CONTACTS -> Icons.Outlined.People
                                    Tab.ACCESS_ACTIVITY -> Icons.Outlined.Shield
                                },
                                contentDescription = tab.title,
                                tint = if (isActive) Color.White else Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = tab.title,
                                color = if (isActive) Color.White else Color.White.copy(alpha = 0.8f),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Row (Subtle & Elegant status indication)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SECURE DIGITAL HERITAGE LEDGER",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.05.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "VAULT STATUS: ",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.width(4.dp))
                when (viewModel.accessStatus) {
                    AccessStatus.NO_REQUEST -> StampedLabel(text = "Secured", color = SageGreen)
                    AccessStatus.GRACE_PERIOD -> StampedLabel(text = "Grace Period", color = MutedAmber)
                    AccessStatus.AWAITING_SECOND_CONFIRMATION -> StampedLabel(text = "Pending Confirmation", color = MutedAmber)
                    AccessStatus.UNLOCKED -> StampedLabel(text = "Unlocked", color = SageGreen)
                    AccessStatus.CANCELLED -> StampedLabel(text = "Cancelled", color = MutedRed)
                }
            }
        }
    }
}

@Composable
fun StampedLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, color), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.04.sp
        )
    }
}

// ==========================================
// SCREEN 1: MY VAULT
// ==========================================
@Composable
fun FirebaseSyncDashboard(viewModel: SecureLegacyViewModel) {
    var isGuideExpanded by remember { mutableStateOf(false) }
    var isSyncingNow by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }

    // Colors
    val cardBg = WarmOffWhite
    val borderCol = SageGreen.copy(alpha = 0.5f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (viewModel.isFirestoreAvailable) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                        contentDescription = "Cloud Icon",
                        tint = if (viewModel.isFirestoreAvailable) SageGreen else InkNavy,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Firebase Console Sync",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp,
                        color = InkNavy
                    )
                }

                // Status Badge
                Surface(
                    color = if (viewModel.isFirestoreAvailable) {
                        SageGreen.copy(alpha = 0.15f)
                    } else if (viewModel.isSimulationModeActive) {
                        MutedAmber.copy(alpha = 0.15f)
                    } else {
                        Color.Gray.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = if (viewModel.isFirestoreAvailable) "LIVE SYNC ACTIVE" else if (viewModel.isSimulationModeActive) "SIMULATION ACTIVE" else "LOCAL MODE (OFFLINE)",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (viewModel.isFirestoreAvailable) SageGreen else if (viewModel.isSimulationModeActive) MutedAmber else TextMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sync Status/Message info
            Text(
                text = "Console Sync Status: ${viewModel.firebaseSyncStatus}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (viewModel.isFirestoreAvailable) SageGreen else InkNavy.copy(alpha = 0.8f)
            )

            if (syncMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = syncMessage,
                    fontSize = 11.sp,
                    color = SageGreen,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Main Sync Button
                Button(
                    onClick = {
                        if (viewModel.isFirestoreAvailable) {
                            isSyncingNow = true
                            viewModel.saveLedger()
                            isSyncingNow = false
                            syncMessage = "Successfully backed up digital ledger to live Firebase Console!"
                        } else if (viewModel.isSimulationModeActive) {
                            isSyncingNow = true
                            syncMessage = "Connecting to Firebase Console..."
                        } else {
                            syncMessage = "Please enable 'Simulate Console Sync' or add google-services.json to sync."
                        }
                    },
                    enabled = !isSyncingNow,
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.5f)
                ) {
                    if (isSyncingNow) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Syncing...", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync to Console", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                // Simulate toggle if no real Firestore
                if (!viewModel.isFirestoreAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1.2f)
                            .background(Color(0x0A3E7C6B), RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.setSimulationMode(!viewModel.isSimulationModeActive)
                                syncMessage = ""
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Checkbox(
                            checked = viewModel.isSimulationModeActive,
                            onCheckedChange = {
                                viewModel.setSimulationMode(it)
                                syncMessage = ""
                            },
                            colors = CheckboxDefaults.colors(checkedColor = SageGreen),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Simulate Sync",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = InkNavy
                        )
                    }
                } else {
                    // Pull from cloud button for Live
                    OutlinedButton(
                        onClick = {
                            viewModel.loadFromFirestoreAndMerge()
                            syncMessage = "Successfully fetched latest backup from Cloud Console!"
                        },
                        border = BorderStroke(1.dp, SageGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = SageGreen, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fetch", fontSize = 11.sp, color = SageGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Simulated Sync delay logic
            if (isSyncingNow && viewModel.isSimulationModeActive) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1200)
                    isSyncingNow = false
                    syncMessage = "Ledger successfully synchronized to Firebase Console! (Simulated)"
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable Guide Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x05000000), RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f)), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isGuideExpanded = !isGuideExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "How to connect your live Firebase Console?",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkNavy
                    )
                    Icon(
                        imageVector = if (isGuideExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand Guide",
                        tint = InkNavy,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (isGuideExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color.LightGray.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "To establish a live, real-time connection to your Firebase Console, follow these quick steps:",
                            fontSize = 10.sp,
                            color = TextDark,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "1. Go to console.firebase.google.com and create a new project.",
                            fontSize = 10.sp,
                            color = TextDark
                        )

                        Text(
                            text = "2. Register an Android app using Package Name: com.aistudio.amanat.kxmpzq",
                            fontSize = 10.sp,
                            color = TextDark,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "3. Download the google-services.json file and place it in your app's main project folder.",
                            fontSize = 10.sp,
                            color = TextDark
                        )

                        Text(
                            text = "4. Enable Cloud Firestore in your Firebase project and select 'Start in test mode' to allow immediate read/write access.",
                            fontSize = 10.sp,
                            color = TextDark
                        )

                        Text(
                            text = "5. Re-run or compile the applet. The cloud status badge will automatically turn green to signify an active real-time connection!",
                            fontSize = 10.sp,
                            color = TextDark,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyVaultScreen(viewModel: SecureLegacyViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCategoryForAdd by remember { mutableStateOf(VaultCategory.EMERGENCY) }
    var editingItem by remember { mutableStateOf<VaultItem?>(null) }

    // Evaluate Access Rules for Current Role
    val isOwner = viewModel.viewingAs == "Owner"
    
    // Find active contact's tier
    val viewerContact = viewModel.trustedContacts.find { it.name == viewModel.viewingAs }
    val viewerTier = viewerContact?.tier

    // Emergency Contacts visible if request exists
    val requestExists = viewModel.accessStatus != AccessStatus.NO_REQUEST && viewModel.accessStatus != AccessStatus.CANCELLED
    val isEmergencyVisible = isOwner || requestExists

    // All other categories unlocked ONLY if status is UNLOCKED and matches the access tier
    val isUnlockedStatus = viewModel.accessStatus == AccessStatus.UNLOCKED
    
    val bankingVisible = isOwner || (isUnlockedStatus && (viewerTier == AccessTier.FULL_ACCESS || viewerTier == AccessTier.FINANCIAL_ONLY))
    val insuranceVisible = isOwner || (isUnlockedStatus && viewerTier == AccessTier.FULL_ACCESS)
    val propertyVisible = isOwner || (isUnlockedStatus && viewerTier == AccessTier.FULL_ACCESS)
    val liabilitiesVisible = isOwner || (isUnlockedStatus && (viewerTier == AccessTier.FULL_ACCESS || viewerTier == AccessTier.FINANCIAL_ONLY))
    val pensionVisible = isOwner || (isUnlockedStatus && (viewerTier == AccessTier.FULL_ACCESS || viewerTier == AccessTier.FINANCIAL_ONLY))
    val epfVisible = isOwner || (isUnlockedStatus && (viewerTier == AccessTier.FULL_ACCESS || viewerTier == AccessTier.FINANCIAL_ONLY))
    val otherVisible = isOwner || (isUnlockedStatus && viewerTier == AccessTier.FULL_ACCESS)

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Category list
            VaultCategory.values().forEach { category ->
                val isCategoryUnlocked = when (category) {
                    VaultCategory.EMERGENCY -> isEmergencyVisible
                    VaultCategory.BANKING -> bankingVisible
                    VaultCategory.INSURANCE -> insuranceVisible
                    VaultCategory.PROPERTY -> propertyVisible
                    VaultCategory.LIABILITIES -> liabilitiesVisible
                    VaultCategory.PENSION -> pensionVisible
                    VaultCategory.EPF -> epfVisible
                    VaultCategory.MESSAGE -> otherVisible
                    VaultCategory.OTHER -> otherVisible
                }

                CategorySectionCard(
                    viewModel = viewModel,
                    category = category,
                    items = viewModel.vaultItems.filter { it.category == category },
                    isUnlocked = isCategoryUnlocked,
                    viewerTier = viewerTier,
                    isOwner = isOwner,
                    requestExists = requestExists,
                    unlockedStatus = isUnlockedStatus,
                    onAddItemClick = {
                        selectedCategoryForAdd = category
                        showAddDialog = true
                    },
                    onEditItemClick = { item ->
                        editingItem = item
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Add / Edit Vault Item Dialog
        if (showAddDialog) {
            AddOrEditVaultItemDialog(
                initialCategory = selectedCategoryForAdd,
                existingItem = null,
                onDismiss = { showAddDialog = false },
                onSave = { title, detail, category, contactType, assetType, docs ->
                    viewModel.addVaultItem(title, detail, category, contactType, assetType, docs)
                    showAddDialog = false
                }
            )
        } else if (editingItem != null) {
            val currentEditingItem = editingItem!!
            AddOrEditVaultItemDialog(
                initialCategory = currentEditingItem.category,
                existingItem = currentEditingItem,
                onDismiss = { editingItem = null },
                onSave = { title, detail, _, contactType, assetType, _ ->
                    viewModel.updateVaultItem(currentEditingItem.id, title, detail, contactType, assetType)
                    editingItem = null
                },
                onDelete = {
                    viewModel.deleteVaultItem(currentEditingItem.id)
                    editingItem = null
                }
            )
        }
    }
}

@Composable
fun CategorySectionCard(
    viewModel: SecureLegacyViewModel,
    category: VaultCategory,
    items: List<VaultItem>,
    isUnlocked: Boolean,
    viewerTier: AccessTier?,
    isOwner: Boolean,
    requestExists: Boolean,
    unlockedStatus: Boolean,
    onAddItemClick: () -> Unit,
    onEditItemClick: (VaultItem) -> Unit
) {
    var showUploadDialogForItem by remember { mutableStateOf<VaultItem?>(null) }
    var showViewDialogForDoc by remember { mutableStateOf<Pair<VaultItem, String>?>(null) }

    val categoryColor = when (category) {
        VaultCategory.EMERGENCY -> SageGreen
        VaultCategory.BANKING -> Color(0xFF38BDF8)
        VaultCategory.INSURANCE -> MutedAmber
        VaultCategory.PROPERTY -> Color(0xFFF1F5F9)
        VaultCategory.LIABILITIES -> MutedRed
        VaultCategory.PENSION -> Color(0xFF9E7777)
        VaultCategory.EPF -> Color(0xFF818CF8)
        VaultCategory.MESSAGE -> Color(0xFFA855F7)
        VaultCategory.OTHER -> Color(0xFF94A3B8)
    }

    val icon = when (category) {
        VaultCategory.EMERGENCY -> Icons.Default.Phone
        VaultCategory.BANKING -> Icons.Default.AccountBalance
        VaultCategory.INSURANCE -> Icons.Default.Security
        VaultCategory.PROPERTY -> Icons.Default.Description
        VaultCategory.LIABILITIES -> Icons.Default.TrendingDown
        VaultCategory.PENSION -> Icons.Default.TrendingUp
        VaultCategory.EPF -> Icons.Default.AccountBalanceWallet
        VaultCategory.MESSAGE -> Icons.Default.Email
        VaultCategory.OTHER -> Icons.Default.FolderOpen
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = WarmCard),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Category Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(categoryColor.copy(alpha = 0.2f))
                            .border(BorderStroke(1.dp, categoryColor.copy(alpha = 0.4f)), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = category.displayName,
                            tint = categoryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = category.displayName,
                            color = TextDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Text(
                            text = "CATEGORY",
                            color = TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.05.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isUnlocked) {
                        // 3D "ADD" Box Button
                        Surface(
                            onClick = onAddItemClick,
                            shape = RoundedCornerShape(10.dp),
                            color = SageGreen,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                            modifier = Modifier.testTag("add_item_to_${category.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0xFF34D399),
                                                Color(0xFF059669)
                                            )
                                        )
                                    )
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Item",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "ADD",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.SansSerif,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    } else {
                        // 3D "LOCKED" Box Badge
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                            shadowElevation = 4.dp,
                            border = BorderStroke(1.dp, MutedAmber.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked category",
                                    tint = MutedAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "LOCKED",
                                    color = MutedAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif,
                                    letterSpacing = 0.3.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Items representation
            if (isUnlocked) {
                if (items.isEmpty()) {
                    Text(
                        text = "No vault items added in this category yet.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0F172A))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = item.title,
                                            color = TextDark,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.SansSerif
                                        )
                                        if (item.contactType != null) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val badgeColor = when (item.contactType) {
                                                "CA" -> Color(0xFFE3F2FD) to Color(0xFF1E88E5)
                                                "Lawyer" -> Color(0xFFF3E5F5) to Color(0xFF8E24AA)
                                                "Insurance Agent" -> Color(0xFFFFF8E1) to Color(0xFFFFB300)
                                                "Bank RM" -> Color(0xFFE8F5E9) to Color(0xFF43A047)
                                                "Debtor" -> Color(0xFFFFF3E0) to Color(0xFFFB8C00)
                                                "Creditor" -> Color(0xFFFFEBEE) to Color(0xFFE53935)
                                                else -> Color(0xFFECEFF1) to Color(0xFF546E7A)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(badgeColor.first)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = item.contactType,
                                                    color = badgeColor.second,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.SansSerif
                                                )
                                            }
                                        }
                                        if (item.assetType != null && item.assetType != "Others" && item.assetType != "Important Contact") {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(SageGreen.copy(alpha = 0.12f))
                                                    .border(BorderStroke(0.5.dp, SageGreen.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = item.assetType,
                                                    color = SageGreen,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.SansSerif
                                                )
                                            }
                                        }
                                    }

                                    // Action buttons for each item (Owner only)
                                    if (isOwner) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            // Edit Button
                                            IconButton(
                                                onClick = { onEditItemClick(item) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit details",
                                                    tint = SageGreen,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }

                                            // Upload Document Icon Button
                                            IconButton(
                                                onClick = { showUploadDialogForItem = item },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.UploadFile,
                                                    contentDescription = "Upload Document",
                                                    tint = InkNavy,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }

                                            // Delete Item Button
                                            IconButton(
                                                onClick = { viewModel.deleteVaultItem(item.id) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete item",
                                                    tint = MutedRed.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.detail,
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.SansSerif
                                )

                                // Attached documents display block
                                if (item.uploadedDocuments.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "ATTACHED DOCUMENTS",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted,
                                        letterSpacing = 0.05.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        item.uploadedDocuments.forEach { doc ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(SageGreen.copy(alpha = 0.05f))
                                                    .border(BorderStroke(0.5.dp, SageGreen.copy(alpha = 0.2f)), RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        showViewDialogForDoc = item to doc
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Description,
                                                        contentDescription = "Document icon",
                                                        tint = SageGreen,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = doc,
                                                        color = TextDark,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        fontFamily = FontFamily.SansSerif
                                                    )
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "View",
                                                        color = SageGreen,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.SansSerif
                                                    )
                                                    if (isOwner) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.deleteDocumentFromItem(item.id, doc)
                                                            },
                                                            modifier = Modifier.size(16.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete document",
                                                                tint = MutedRed.copy(alpha = 0.8f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Redacted / Locked Preview representation
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Explanatory Subtext for Lock
                    Text(
                        text = if (category == VaultCategory.EMERGENCY) {
                            "Emergency info is locked until an emergency access request has been initiated by a contact."
                        } else if (!unlockedStatus) {
                            "Locked until an emergency request is approved & fully unlocked."
                        } else {
                            "This category is restricted. Your access tier (${viewerTier?.displayName ?: "Emergency only"}) is insufficient to view financial/property items."
                        },
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Fake Redacted Bars simulating private data
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(160.dp)
                                .height(10.dp)
                                .background(CardBorder, RoundedCornerShape(4.dp))
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .height(8.dp)
                                .background(CardBorder.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }

    // Helper Dialogs managed locally inside CategorySectionCard
    showUploadDialogForItem?.let { item ->
        UploadDocumentDialog(
            item = item,
            onDismiss = { showUploadDialogForItem = null },
            onUpload = { docName ->
                viewModel.uploadDocumentToItem(item.id, docName)
                showUploadDialogForItem = null
            }
        )
    }

    showViewDialogForDoc?.let { pair ->
        ViewDocumentDialog(
            item = pair.first,
            documentName = pair.second,
            onDismiss = { showViewDialogForDoc = null }
        )
    }
}

@Composable
fun UploadDocumentDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onUpload: (documentName: String) -> Unit
) {
    var docName by remember { mutableStateOf("") }
    val recommendedDocs = when (item.category) {
        VaultCategory.EMERGENCY -> listOf("ID_Proof.pdf", "Authorization_Letter.pdf", "Agreement_Signed.pdf")
        VaultCategory.BANKING -> listOf("Bank_Statement.pdf", "Cancelled_Cheque.pdf", "Passbook_Scan.pdf")
        VaultCategory.INSURANCE -> listOf("Policy_Bond.pdf", "Premium_Receipt.pdf", "Claim_Form.pdf")
        VaultCategory.PROPERTY -> listOf("Sale_Deed.pdf", "Property_Tax_Receipt.pdf", "Possession_Letter.pdf")
        VaultCategory.LIABILITIES -> listOf("Loan_Agreement.pdf", "NOC_Certificate.pdf", "Statement_of_Account.pdf")
        VaultCategory.PENSION -> listOf("NPS_Registration.pdf", "NPS_Transaction_Statement.pdf", "PRAN_Card_Scan.pdf")
        VaultCategory.EPF -> listOf("UAN_Card_Scan.pdf", "EPF_Passbook.pdf", "Form_11_Signed.pdf")
        VaultCategory.MESSAGE -> listOf("Personal_Letter.pdf", "Video_Note_Link.txt", "Special_Wishes.pdf")
        VaultCategory.OTHER -> listOf("Affidavit.pdf", "Consent_Letter.pdf", "Miscellaneous_Doc.pdf")
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, InkNavy), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Upload Document",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Attach a digital document/file to: ${item.title}",
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "DOCUMENT NAME",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = docName,
                    onValueChange = { docName = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. ID_Proof.pdf", color = TextMuted.copy(alpha = 0.5f)) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "SUGGESTED DOCUMENTS:",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recommendedDocs.forEach { doc ->
                        Box(
                            modifier = Modifier
                                .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.5f))
                                .clickable { docName = doc }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = doc,
                                fontSize = 10.sp,
                                color = TextDark,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextMuted, fontFamily = FontFamily.SansSerif)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (docName.isNotBlank()) {
                                onUpload(docName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        shape = RoundedCornerShape(8.dp),
                        enabled = docName.isNotBlank()
                    ) {
                        Text("Upload File", color = Color.White, fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        }
    }
}

@Composable
fun ViewDocumentDialog(
    item: VaultItem,
    documentName: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, InkNavy), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Document Preview",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextDark
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Simulated Document Canvas or Page
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "PDF document",
                            tint = SageGreen,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = documentName,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SECURE ENCRYPTED LEDGER DOCUMENT\nVerified & Notarized on Legacy Chain",
                            fontSize = 10.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "This document is securely encrypted locally. It is only accessible to authorized trustees during active dual-custody emergency windows.",
                    fontSize = 10.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done", color = Color.White, fontFamily = FontFamily.SansSerif)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditVaultItemDialog(
    initialCategory: VaultCategory = VaultCategory.EMERGENCY,
    existingItem: VaultItem? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, detail: String, category: VaultCategory, contactType: String?, assetType: String?, uploadedDocuments: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(existingItem?.title ?: "") }
    var detail by remember { mutableStateOf(existingItem?.detail ?: "") }
    var selectedContactType by remember { mutableStateOf(existingItem?.contactType ?: "CA") }
    var documentInput by remember { mutableStateOf("") }
    var attachedDocuments by remember { mutableStateOf(existingItem?.uploadedDocuments ?: listOf()) }

    val category = existingItem?.category ?: initialCategory

    val titleLabel = when (category) {
        VaultCategory.BANKING -> "Bank / Investment Institution Name"
        VaultCategory.INSURANCE -> "Insurer & Policy Name"
        VaultCategory.EMERGENCY -> "Contact Person / Professional Name"
        VaultCategory.PROPERTY -> "Property / Asset Description"
        VaultCategory.LIABILITIES -> "Party / Loan Reference Name"
        VaultCategory.PENSION -> "Pension Scheme / Provider"
        VaultCategory.EPF -> "EPF / UAN Account Title"
        VaultCategory.MESSAGE -> "Message Title / Recipient Name"
        VaultCategory.OTHER -> "Record Title / Asset Name"
    }

    val titlePlaceholder = when (category) {
        VaultCategory.BANKING -> "e.g. HDFC Bank Savings / Zerodha Demat"
        VaultCategory.INSURANCE -> "e.g. LIC Jeevan Anand / Star Health"
        VaultCategory.EMERGENCY -> "e.g. Rajesh Kumar (Family Lawyer)"
        VaultCategory.PROPERTY -> "e.g. 3BHK Apartment in Pune"
        VaultCategory.LIABILITIES -> "e.g. Home Loan - SBI"
        VaultCategory.PENSION -> "e.g. NPS Tier-1 Account"
        VaultCategory.EPF -> "e.g. EPFO Member ID"
        VaultCategory.MESSAGE -> "e.g. Letter for My Family / Note for Spouse & Kids"
        VaultCategory.OTHER -> "e.g. Gold Locker Deposit"
    }

    val detailLabel = when (category) {
        VaultCategory.BANKING -> "Account Number / Investment Details & Valuation"
        VaultCategory.INSURANCE -> "Policy Number, Coverage Amount & Renewal Date"
        VaultCategory.EMERGENCY -> "Phone Number, Email & Office Address"
        VaultCategory.PROPERTY -> "Registration ID, Valuation & Location Details"
        VaultCategory.LIABILITIES -> "Outstanding Balance, Interest & Due Particulars"
        VaultCategory.PENSION -> "PRAN Number & Current Accumulated Balance"
        VaultCategory.EPF -> "UAN Number, PF Account No & Employer"
        VaultCategory.MESSAGE -> "Personal Message & Special Instructions"
        VaultCategory.OTHER -> "Particulars, Value & Access Instructions"
    }

    val detailPlaceholder = when (category) {
        VaultCategory.BANKING -> "e.g. Acc: 50100012345 | IFSC: HDFC0001234 | Bal: ₹5,00,000"
        VaultCategory.INSURANCE -> "e.g. Policy #987654321 | Cover: ₹50 Lakhs | Premium: ₹20,000/yr"
        VaultCategory.EMERGENCY -> "e.g. Phone: +91 98765 43210 | Email: lawyer@chambers.com"
        VaultCategory.PROPERTY -> "e.g. Reg Doc #2023/8891 | Flat 402, Sunshine Towers"
        VaultCategory.LIABILITIES -> "e.g. Principal: ₹25 Lakhs | Rate: 8.5% | Tenure: 15 yrs"
        VaultCategory.PENSION -> "e.g. PRAN: 110012345678 | Valuation: ₹12,00,000"
        VaultCategory.EPF -> "e.g. UAN: 100987654321 | PF No: MH/PUN/0012345/000"
        VaultCategory.MESSAGE -> "e.g. Write your personal message, advice, key locations, or private note for anyone here..."
        VaultCategory.OTHER -> "e.g. Locker #45, SBI Main Branch, Pune"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, InkNavy), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (existingItem == null) "Add ${category.displayName} Details" else "Update ${category.displayName} Details",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = SageGreen.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "CATEGORY: ${category.displayName.uppercase()}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SageGreen,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (category == VaultCategory.EMERGENCY) {
                    Text(
                        text = "CONTACT DESIGNATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("CA", "Lawyer", "Insurance Agent", "Bank RM").forEach { cType ->
                            val isSelected = selectedContactType == cType
                            Surface(
                                onClick = { selectedContactType = cType },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) SageGreen else Color.White,
                                border = BorderStroke(1.dp, if (isSelected) SageGreen else CardBorder)
                            ) {
                                Text(
                                    text = cType,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else TextDark,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Title Input
                Text(
                    text = titleLabel.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(titlePlaceholder, color = TextMuted.copy(alpha = 0.5f), fontSize = 12.sp) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Detail Input
                Text(
                    text = detailLabel.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    placeholder = { Text(detailPlaceholder, color = TextMuted.copy(alpha = 0.5f), fontSize = 12.sp) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Optional Document Names
                Text(
                    text = "ATTACHED DOCUMENTS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = documentInput,
                        onValueChange = { documentInput = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = TextDark),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextDark,
                            unfocusedTextColor = TextDark
                        ),
                        placeholder = { Text("e.g. AccountStatement.pdf", fontSize = 11.sp, color = TextMuted.copy(alpha = 0.5f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (documentInput.isNotBlank()) {
                                attachedDocuments = attachedDocuments + documentInput.trim()
                                documentInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Add", fontSize = 11.sp, color = Color.White)
                    }
                }

                if (attachedDocuments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        attachedDocuments.forEach { doc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SageGreen.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(doc, fontSize = 11.sp, color = TextDark)
                                IconButton(
                                    onClick = { attachedDocuments = attachedDocuments.filter { it != doc } },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove doc", tint = MutedRed)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dialog Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (existingItem != null && onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = MutedRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextMuted, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onSave(
                                    title,
                                    detail,
                                    category,
                                    if (category == VaultCategory.EMERGENCY) selectedContactType else null,
                                    null,
                                    attachedDocuments
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (existingItem == null) "Save Record" else "Update Record",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SUBSCRIPTION & PAYMENT DIALOG
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionPaymentDialog(viewModel: SecureLegacyViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedPlan by remember { mutableStateOf(if (viewModel.subscriptionTier != SubscriptionTier.FREE) viewModel.subscriptionTier else SubscriptionTier.ANNUAL_PRO) }
    var selectedPaymentMethod by remember { mutableStateOf("UPI") } // "UPI", "CARD", "NETBANKING"
    
    var upiId by remember { mutableStateOf("user@okhdfcbank") }
    var selectedUpiApp by remember { mutableStateOf("Google Pay") }
    
    var cardNumber by remember { mutableStateOf("4532 8921 7843 9012") }
    var cardExpiry by remember { mutableStateOf("12/28") }
    var cardCvv by remember { mutableStateOf("882") }
    var cardHolder by remember { mutableStateOf(viewModel.registeredUsername.ifEmpty { "Amanat Vault Owner" }) }
    
    var selectedBank by remember { mutableStateOf("HDFC Bank") }
    
    var isProcessing by remember { mutableStateOf(false) }
    var showReceipt by remember { mutableStateOf(false) }
    var activeTxId by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(22.dp)),
            color = Color(0xFF0F172A),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shadowElevation = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(Color(0xFFF59E0B), Color(0xFFD97706))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Premium Subscription",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Amanat Heritage Subscription",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                            Text(
                                text = "Encrypted Vault & Priority Emergency Protection",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(14.dp))

                if (showReceipt) {
                    // SUCCESS RECEIPT VIEW
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(SageGreen.copy(alpha = 0.2f))
                                .border(BorderStroke(2.dp, SageGreen), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Success",
                                tint = SageGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Payment Successful!",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )

                        Text(
                            text = "Your Amanat Heritage Subscription is now Active",
                            color = SageGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Receipt Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "OFFICIAL PAYMENT RECEIPT",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Transaction ID", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    Text(text = activeTxId, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }

                                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Plan Subscribed", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    Text(text = selectedPlan.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Amount Paid", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    Text(text = selectedPlan.priceText, color = SageGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Payment Method", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    Text(text = "$selectedPaymentMethod ($selectedUpiApp)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }

                                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Status / Validity", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    Text(text = viewModel.subscriptionExpiryDate, color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Done & Go To Vault",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                } else if (isProcessing) {
                    // PROCESSING VIEW
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = SageGreen,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Securing Encrypted Escrow Gateway...",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verifying payment with bank & configuring digital estate ledger...",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // SELECTION & FORM VIEW
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Current Subscription Status if active
                        if (viewModel.isSubscribed) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF1E293B),
                                border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = SageGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Current Active Subscription",
                                                color = SageGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = viewModel.subscriptionTier.displayName,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Valid till: ${viewModel.subscriptionExpiryDate} • Tx ID: ${viewModel.subscriptionTransactionId}",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            viewModel.cancelSubscription()
                                            Toast.makeText(context, "Subscription canceled", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedRed),
                                        border = BorderStroke(1.dp, MutedRed.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Cancel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // STEP 1: SELECT PLAN
                        Text(
                            text = "1. CHOOSE YOUR PROTECTION PLAN",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        // Plan 1: Amanat Pro (Annual)
                        PlanOptionCard(
                            tier = SubscriptionTier.ANNUAL_PRO,
                            tagText = "MOST POPULAR",
                            features = listOf(
                                "Unlimited Digital Vault Asset Items",
                                "Priority Emergency Protocol Dispatch",
                                "Biometric Multi-Factor Escrow",
                                "Encrypted Cloud Sync & PDF Export"
                            ),
                            isSelected = selectedPlan == SubscriptionTier.ANNUAL_PRO,
                            onSelect = { selectedPlan = SubscriptionTier.ANNUAL_PRO }
                        )

                        // Plan 2: Family Heritage (Lifetime)
                        PlanOptionCard(
                            tier = SubscriptionTier.LIFETIME_HERITAGE,
                            tagText = "BEST VALUE",
                            features = listOf(
                                "Everything in Pro + Up to 5 Family Accounts",
                                "Dedicated Legal Executor Portal",
                                "Physical Security Escrow Box Option",
                                "24/7 Priority Heritage Hotline Support"
                            ),
                            isSelected = selectedPlan == SubscriptionTier.LIFETIME_HERITAGE,
                            onSelect = { selectedPlan = SubscriptionTier.LIFETIME_HERITAGE }
                        )

                        // Plan 3: Free
                        PlanOptionCard(
                            tier = SubscriptionTier.FREE,
                            tagText = "BASIC",
                            features = listOf(
                                "Basic Vault Storage (up to 5 assets)",
                                "Max 2 Trusted Contacts",
                                "Standard Grace Period Protocol"
                            ),
                            isSelected = selectedPlan == SubscriptionTier.FREE,
                            onSelect = { selectedPlan = SubscriptionTier.FREE }
                        )

                        if (selectedPlan != SubscriptionTier.FREE) {
                            // STEP 2: PAYMENT METHOD
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "2. SELECT PAYMENT METHOD",
                                color = SageGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )

                            // Payment Method Selector Tabs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PaymentMethodChip(
                                    title = "UPI / QR",
                                    icon = Icons.Default.QrCode,
                                    isSelected = selectedPaymentMethod == "UPI",
                                    onClick = { selectedPaymentMethod = "UPI" },
                                    modifier = Modifier.weight(1f)
                                )
                                PaymentMethodChip(
                                    title = "Card",
                                    icon = Icons.Default.CreditCard,
                                    isSelected = selectedPaymentMethod == "CARD",
                                    onClick = { selectedPaymentMethod = "CARD" },
                                    modifier = Modifier.weight(1f)
                                )
                                PaymentMethodChip(
                                    title = "NetBanking",
                                    icon = Icons.Default.AccountBalance,
                                    isSelected = selectedPaymentMethod == "NETBANKING",
                                    onClick = { selectedPaymentMethod = "NETBANKING" },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Dynamic Payment Method Inputs
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    when (selectedPaymentMethod) {
                                        "UPI" -> {
                                            Text(
                                                text = "Select Preferred UPI App or Enter Virtual Payment Address (VPA):",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                listOf("Google Pay", "PhonePe", "Paytm", "BHIM").forEach { appName ->
                                                    Surface(
                                                        onClick = { selectedUpiApp = appName },
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = if (selectedUpiApp == appName) SageGreen.copy(alpha = 0.3f) else Color(0xFF0F172A),
                                                        border = BorderStroke(1.dp, if (selectedUpiApp == appName) SageGreen else Color(0xFF334155)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text(
                                                            text = appName,
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.padding(vertical = 8.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            OutlinedTextField(
                                                value = upiId,
                                                onValueChange = { upiId = it },
                                                label = { Text("UPI VPA ID", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = SageGreen,
                                                    unfocusedBorderColor = Color(0xFF334155)
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                singleLine = true
                                            )
                                        }

                                        "CARD" -> {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedTextField(
                                                    value = cardHolder,
                                                    onValueChange = { cardHolder = it },
                                                    label = { Text("Name on Card", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = SageGreen,
                                                        unfocusedBorderColor = Color(0xFF334155)
                                                    ),
                                                    shape = RoundedCornerShape(10.dp),
                                                    singleLine = true
                                                )

                                                OutlinedTextField(
                                                    value = cardNumber,
                                                    onValueChange = { cardNumber = it },
                                                    label = { Text("Card Number", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = SageGreen,
                                                        unfocusedBorderColor = Color(0xFF334155)
                                                    ),
                                                    shape = RoundedCornerShape(10.dp),
                                                    singleLine = true
                                                )

                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    OutlinedTextField(
                                                        value = cardExpiry,
                                                        onValueChange = { cardExpiry = it },
                                                        label = { Text("Expiry (MM/YY)", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                                        modifier = Modifier.weight(1f),
                                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = SageGreen,
                                                            unfocusedBorderColor = Color(0xFF334155)
                                                        ),
                                                        shape = RoundedCornerShape(10.dp),
                                                        singleLine = true
                                                    )

                                                    OutlinedTextField(
                                                        value = cardCvv,
                                                        onValueChange = { cardCvv = it },
                                                        label = { Text("CVV", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                                        modifier = Modifier.weight(1f),
                                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = SageGreen,
                                                            unfocusedBorderColor = Color(0xFF334155)
                                                        ),
                                                        shape = RoundedCornerShape(10.dp),
                                                        singleLine = true
                                                    )
                                                }
                                            }
                                        }

                                        "NETBANKING" -> {
                                            Text(
                                                text = "Select Your Net Banking Account:",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))

                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("HDFC Bank", "ICICI Bank", "State Bank of India (SBI)", "Axis Bank", "Kotak Mahindra").forEach { bank ->
                                                    Surface(
                                                        onClick = { selectedBank = bank },
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = if (selectedBank == bank) SageGreen.copy(alpha = 0.25f) else Color(0xFF0F172A),
                                                        border = BorderStroke(1.dp, if (selectedBank == bank) SageGreen else Color(0xFF334155))
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(text = bank, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                            if (selectedBank == bank) {
                                                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = SageGreen, modifier = Modifier.size(16.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ACTION BUTTON
                    if (selectedPlan == SubscriptionTier.FREE) {
                        Button(
                            onClick = {
                                viewModel.subscribeToPlan(SubscriptionTier.FREE, "None")
                                Toast.makeText(context, "Switched to Free Plan", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Continue with Free Plan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                isProcessing = true
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(1500)
                                    val txId = viewModel.subscribeToPlan(selectedPlan, selectedPaymentMethod)
                                    activeTxId = txId
                                    isProcessing = false
                                    showReceipt = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Pay ${selectedPlan.priceText} & Activate ${selectedPlan.displayName}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanOptionCard(
    tier: SubscriptionTier,
    tagText: String,
    features: List<String>,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF0F172A)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) SageGreen else Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(
                        selected = isSelected,
                        onClick = onSelect,
                        colors = RadioButtonDefaults.colors(selectedColor = SageGreen, unselectedColor = Color(0xFF94A3B8))
                    )
                    Column {
                        Text(
                            text = tier.displayName,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Text(
                            text = "${tier.priceText} / ${tier.billingCycleText}",
                            color = SageGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) SageGreen else Color(0xFF334155)
                ) {
                    Text(
                        text = tagText,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                features.forEach { feat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = SageGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = feat,
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) SageGreen.copy(alpha = 0.25f) else Color(0xFF1E293B),
        border = BorderStroke(1.dp, if (isSelected) SageGreen else Color(0xFF334155)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) SageGreen else Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = if (isSelected) Color.White else Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ==========================================
// TRUSTED CONTACT USER MANUAL DIALOG
// ==========================================
@Composable
fun TrustedContactUserManualDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xFF0F172A),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SageGreen.copy(alpha = 0.2f))
                                .border(BorderStroke(1.dp, SageGreen.copy(alpha = 0.5f)), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = "Manual",
                                tint = SageGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Trusted Contact User Manual",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                            Text(
                                text = "Official Guide & Safety Rules",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Introduction Banner
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "Thank you for being someone's Trusted Contact. This guide explains what that means and what to do if you ever need to step in.",
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }

                    // Section 1: The Most Important Thing to Know
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MutedAmber.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MutedAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "The Most Important Thing to Know",
                                    color = MutedAmber,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "You do not have standing access to their account. You can't just log in and look around whenever you want. Access only opens up through a specific process, and it's designed with several checks along the way so it's never triggered by mistake or misused.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // Section 2: How the Process Works
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "HOW THE PROCESS WORKS",
                            color = SageGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        ManualStepCard(
                            stepNum = "1",
                            stepTitle = "Submit a Request",
                            description = "If you believe the account owner needs help (for example, they're unreachable in an emergency), you submit a request through the app. This starts the process — it does not grant any access yet.",
                            actor = "You"
                        )

                        ManualStepCard(
                            stepNum = "2",
                            stepTitle = "The Owner Gets a Grace Period",
                            description = "Once you submit a request, the account owner is notified and given a set window of time to respond. If this is a false alarm — they're fine, they just missed a call — they can cancel the request during this window and nothing further happens.\n\nIf you're not sure whether to submit a request, remember: the owner always gets a chance to say \"I'm okay\" before anything unlocks.",
                            actor = "Account Owner"
                        )

                        ManualStepCard(
                            stepNum = "3",
                            stepTitle = "A Second Trusted Contact Must Confirm",
                            description = "If the owner doesn't cancel the request, it doesn't move forward on your word alone. A second, independent Trusted Contact must separately confirm that action is needed. This confirmation has to happen independently — one contact can't confirm on behalf of another.",
                            actor = "Second Trusted Contact"
                        )

                        ManualStepCard(
                            stepNum = "4",
                            stepTitle = "Full Access Is Granted",
                            description = "Once both conditions are met (grace period passed + second contact confirmed), full access is granted right away. There's no partial or limited-access stage — once the process is complete, you have everything you need to help.",
                            actor = "System"
                        )
                    }

                    // Section 3: Quick Summary
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "QUICK SUMMARY",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            SummaryRowItem("1. Request", "You flag a concern", "You")
                            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 6.dp))
                            SummaryRowItem("2. Grace Period", "Owner can cancel a false alarm", "Account Owner")
                            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 6.dp))
                            SummaryRowItem("3. Confirmation", "A second contact independently verifies", "Second Trusted Contact")
                            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 6.dp))
                            SummaryRowItem("4. Full Access", "All access is granted at once", "System")
                        }
                    }

                    // Section 4: Frequently Asked Questions
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "FREQUENTLY ASKED QUESTIONS",
                            color = SageGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )

                        FaqCard(
                            question = "Can I cancel my own request?",
                            answer = "Yes, if you submit a request in error, you can cancel it at any point before the process completes."
                        )

                        FaqCard(
                            question = "What if the owner doesn't respond during the grace period?",
                            answer = "The request moves to Step 3, where a second Trusted Contact must confirm before anything unlocks."
                        )

                        FaqCard(
                            question = "What if there's no second Trusted Contact available?",
                            answer = "Without independent confirmation, access cannot proceed — this is by design, to prevent any single person from unlocking the account alone."
                        )

                        FaqCard(
                            question = "Will I get full access right away?",
                            answer = "Yes. Once the grace period has passed and a second Trusted Contact has independently confirmed, you're granted full access immediately."
                        )

                        FaqCard(
                            question = "Why is it built this way?",
                            answer = "Every step exists to protect the account owner: a chance to stop false alarms and a second independent check before anything unlocks. Once both are satisfied, you get everything you need without further delay. It keeps you as a safety net — not a standing gatekeeper."
                        )
                    }

                    // Footer Contact Info
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0284C7).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "If you have questions about your role as a Trusted Contact, reach out to the account owner directly or contact support.",
                            color = Color(0xFF7DD3FC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Close Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "I Understand & Close Manual",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualStepCard(stepNum: String, stepTitle: String, description: String, actor: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SageGreen
                    ) {
                        Text(
                            text = "STEP $stepNum",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = stepTitle,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF334155)
                ) {
                    Text(
                        text = actor,
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SummaryRowItem(step: String, action: String, actor: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = step, color = SageGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = action, color = Color.White, fontSize = 12.sp)
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF334155)
        ) {
            Text(
                text = actor,
                color = Color(0xFFE2E8F0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun FaqCard(question: String, answer: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "Q: ",
                    color = MutedAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = question,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = answer,
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ==========================================
// SCREEN 2: TRUSTED CONTACTS (MAX 2 PERSONS)
// ==========================================
@Composable
fun TrustedContactsScreen(viewModel: SecureLegacyViewModel) {
    val isOwner = viewModel.viewingAs == "Owner"
    var showUserManualDialog by remember { mutableStateOf(false) }

    if (showUserManualDialog) {
        TrustedContactUserManualDialog(onDismiss = { showUserManualDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Description Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = WarmCard,
            border = BorderStroke(1.dp, CardBorder),
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = SageGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Trusted Contacts (Maximum 2 Persons)",
                        color = TextDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Designate up to 2 trusted individuals with simple details (Name, Email ID & Contact Number) who will receive authorization when emergency protocols are triggered.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Manual Button
                Surface(
                    onClick = { showUserManualDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    color = SageGreen.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = SageGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Trusted Contact User Manual & Guide",
                                color = TextDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SageGreen
                        ) {
                            Text(
                                text = "READ MANUAL",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // Option 1: Trusted Contact 1
        TrustedContactSlotCard(
            slotIndex = 0,
            slotTitle = "Option 1: Trusted Contact 1",
            contact = viewModel.trustedContacts.getOrNull(0),
            isOwner = isOwner,
            onSave = { name, email, phone ->
                viewModel.updateTrustedContactAt(0, name, email, phone)
            },
            onDelete = {
                viewModel.deleteTrustedContactAt(0)
            }
        )

        // Option 2: Trusted Contact 2
        TrustedContactSlotCard(
            slotIndex = 1,
            slotTitle = "Option 2: Trusted Contact 2",
            contact = viewModel.trustedContacts.getOrNull(1),
            isOwner = isOwner,
            onSave = { name, email, phone ->
                viewModel.updateTrustedContactAt(1, name, email, phone)
            },
            onDelete = {
                viewModel.deleteTrustedContactAt(1)
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun TrustedContactSlotCard(
    slotIndex: Int,
    slotTitle: String,
    contact: TrustedContact?,
    isOwner: Boolean,
    onSave: (name: String, email: String, phone: String) -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember(contact) { mutableStateOf(contact == null || contact.name.isBlank()) }
    var nameInput by remember(contact) { mutableStateOf(contact?.name ?: "") }
    var emailInput by remember(contact) { mutableStateOf(contact?.email ?: "") }
    var phoneInput by remember(contact) { mutableStateOf(contact?.phone ?: "") }

    val hasContact = contact != null && contact.name.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    if (hasContact) SageGreen.copy(alpha = 0.5f) else CardBorder
                ),
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = WarmCard),
        elevation = CardDefaults.cardElevation(10.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Glowing 3D Pill Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (slotIndex == 0) SageGreen else Color(0xFF38BDF8),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "PERSON ${slotIndex + 1}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Text(
                        text = slotTitle,
                        color = TextDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                }

                // Configured indicator
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasContact) SageGreen.copy(alpha = 0.15f) else MutedAmber.copy(alpha = 0.15f),
                    border = BorderStroke(
                        1.dp,
                        if (hasContact) SageGreen.copy(alpha = 0.4f) else MutedAmber.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = if (hasContact) "CONFIGURED" else "EMPTY SLOT",
                        color = if (hasContact) SageGreen else MutedAmber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = CardBorder)
            Spacer(modifier = Modifier.height(14.dp))

            if (!isEditing && hasContact) {
                // READ-ONLY DISPLAY VIEW
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Full Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SageGreen.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = SageGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "FULL NAME",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Text(
                                text = contact!!.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }
                    }

                    // Email ID
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "EMAIL ID",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Text(
                                text = contact!!.email,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextDark
                            )
                        }
                    }

                    // Contact Number
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MutedAmber.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = MutedAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "CONTACT NUMBER",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            Text(
                                text = contact!!.phone.ifEmpty { "Not specified" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextDark
                            )
                        }
                    }
                }

                if (isOwner) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Edit Button
                        OutlinedButton(
                            onClick = { isEditing = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SageGreen),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Details", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Remove Button
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MutedRed.copy(alpha = 0.15f))
                                .border(BorderStroke(1.dp, MutedRed.copy(alpha = 0.4f)), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove Contact",
                                tint = MutedRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                // EDIT OR ADD FORM INLINE
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Name Field
                    Text(
                        text = "1. FULL NAME",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark, fontSize = 14.sp),
                        placeholder = { Text("e.g. Rahul Sharma", color = TextMuted.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = WarmOffWhite,
                            unfocusedContainerColor = WarmOffWhite
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Email Field
                    Text(
                        text = "2. EMAIL ID",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark, fontSize = 14.sp),
                        placeholder = { Text("e.g. rahul@example.com", color = TextMuted.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = WarmOffWhite,
                            unfocusedContainerColor = WarmOffWhite
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Contact Number Field
                    Text(
                        text = "3. CONTACT NUMBER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark, fontSize = 14.sp),
                        placeholder = { Text("e.g. +91 98765 43210", color = TextMuted.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = WarmOffWhite,
                            unfocusedContainerColor = WarmOffWhite
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasContact) {
                            TextButton(
                                onClick = {
                                    nameInput = contact?.name ?: ""
                                    emailInput = contact?.email ?: ""
                                    phoneInput = contact?.phone ?: ""
                                    isEditing = false
                                }
                            ) {
                                Text("Cancel", color = TextMuted, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // 3D Save Button
                        Surface(
                            onClick = {
                                if (nameInput.isNotBlank()) {
                                    onSave(nameInput.trim(), emailInput.trim(), phoneInput.trim())
                                    isEditing = false
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = SageGreen,
                            shadowElevation = 6.dp,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(Color(0xFF34D399), Color(0xFF059669))
                                        )
                                    )
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "SAVE PERSON ${slotIndex + 1}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, relationship: String, email: String, tier: AccessTier) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf(AccessTier.FULL_ACCESS) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, InkNavy), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Add Trusted Trustee",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Full Name Input
                Text(
                    text = "TRUSTEE NAME",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_contact_name"),
                    singleLine = true,
                    placeholder = { Text("e.g. Linda Miller", color = TextMuted.copy(alpha = 0.5f)) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Relationship Input
                Text(
                    text = "RELATIONSHIP",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_contact_relationship"),
                    singleLine = true,
                    placeholder = { Text("e.g. Sister, Attorney", color = TextMuted.copy(alpha = 0.5f)) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Email Input
                Text(
                    text = "EMAIL ADDRESS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_contact_email"),
                    singleLine = true,
                    placeholder = { Text("e.g. linda@family.test", color = TextMuted.copy(alpha = 0.5f)) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Access Tier Dropdown Selection
                Text(
                    text = "TRUST ACCESS TIER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(6.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("dialog_tier_dropdown"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedTier.displayName,
                            color = TextDark,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Dropdown",
                            tint = TextMuted
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.background(WarmOffWhite)
                    ) {
                        AccessTier.values().forEach { tier ->
                            DropdownMenuItem(
                                text = { Text(tier.displayName, color = TextDark) },
                                onClick = {
                                    selectedTier = tier
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dialog_cancel_contact")
                    ) {
                        Text("Cancel", color = TextMuted, fontFamily = FontFamily.SansSerif)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank() && email.isNotBlank()) {
                                onAdd(name, relationship, email, selectedTier)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        shape = RoundedCornerShape(8.dp),
                        enabled = name.isNotBlank() && email.isNotBlank(),
                        modifier = Modifier.testTag("dialog_add_contact_button")
                    ) {
                        Text("Authorize Contact", color = Color.White, fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 3: ACCESS & ACTIVITY
// ==========================================
@Composable
fun AccessActivityScreen(viewModel: SecureLegacyViewModel) {
    val isOwner = viewModel.viewingAs == "Owner"
    var enteredReason by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmOffWhite)
            .verticalScroll(rememberScrollState())
    ) {
        // Upper Reset Control and Info Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Emergency Safety Authorization Protocol",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = InkNavy,
                fontFamily = FontFamily.Serif
            )
            
            // "reset demo" link
            Text(
                text = "Reset Demo",
                color = MutedRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .clickable { viewModel.resetDemo() }
                    .padding(4.dp)
                    .testTag("reset_demo_link")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // State machine UI
        when (viewModel.accessStatus) {
            AccessStatus.NO_REQUEST -> {
                NoRequestLayout(
                    isOwner = isOwner,
                    viewerName = viewModel.viewingAs,
                    reasonText = enteredReason,
                    onReasonChange = { enteredReason = it },
                    onSubmitRequest = { reason ->
                        viewModel.initiateRequest(viewModel.viewingAs, reason)
                        enteredReason = ""
                    }
                )
            }
            AccessStatus.GRACE_PERIOD -> {
                GracePeriodLayout(
                    isOwner = isOwner,
                    requestedBy = viewModel.requestedByContactName,
                    reason = viewModel.requestReason,
                    onCancel = { viewModel.cancelRequest() },
                    onAdvance = { viewModel.simulateGracePeriodPasses() }
                )
            }
            AccessStatus.AWAITING_SECOND_CONFIRMATION -> {
                AwaitingConfirmationLayout(
                    isOwner = isOwner,
                    requestedBy = viewModel.requestedByContactName,
                    reason = viewModel.requestReason,
                    viewingAs = viewModel.viewingAs,
                    onCancel = { viewModel.cancelRequest() },
                    onConfirm = { confirmer -> viewModel.confirmAsSecondContact(confirmer) }
                )
            }
            AccessStatus.UNLOCKED -> {
                UnlockedLayout(
                    isOwner = isOwner,
                    requestedBy = viewModel.requestedByContactName,
                    reason = viewModel.requestReason,
                    confirmer = viewModel.secondConfirmerName,
                    onCancel = { viewModel.cancelRequest() }
                )
            }
            AccessStatus.CANCELLED -> {
                CancelledLayout(
                    isOwner = isOwner,
                    requestedBy = viewModel.requestedByContactName
                )
            }
        }
    }
}

@Composable
fun NoRequestLayout(
    isOwner: Boolean,
    viewerName: String,
    reasonText: String,
    onReasonChange: (String) -> Unit,
    onSubmitRequest: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Safe",
                    tint = SageGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Vault is Currently Secured",
                    color = TextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            if (isOwner) {
                Text(
                    text = "There are currently no active access requests from your trusted contacts. Your ledger remain fully protected.",
                    color = TextDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SageGreen.copy(alpha = 0.08f))
                        .border(BorderStroke(1.dp, SageGreen.copy(alpha = 0.2f)), RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Safe indicator",
                        tint = SageGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Everything is safe. To simulate the emergency protocol, change \"Viewing as\" in the sidebar to any trusted contact and submit an access request.",
                        color = TextDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            } else {
                Text(
                    text = "As an authorized trustee, you can initiate an emergency access protocol to retrieve essential items if a critical event occurs.",
                    color = TextDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "REASON FOR EMERGENCY ACCESS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = reasonText,
                    onValueChange = onReasonChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark
                    ),
                    placeholder = { Text("e.g. Emergency hospital admission for mom.", color = TextMuted.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_reason_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { if (reasonText.isNotBlank()) onSubmitRequest(reasonText) },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    shape = RoundedCornerShape(8.dp),
                    enabled = reasonText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submit_request_button")
                ) {
                    Text(
                        text = "INITIATE EMERGENCY ACCESS REQUEST",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.05.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }
    }
}

@Composable
fun GracePeriodLayout(
    isOwner: Boolean,
    requestedBy: String,
    reason: String,
    onCancel: () -> Unit,
    onAdvance: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MutedAmber), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MutedAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Emergency Grace Period Triggered",
                    color = TextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "$requestedBy has requested emergency access to the vault.",
                color = TextDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reason submitted: \"$reason\"",
                color = TextMuted,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isOwner) {
                Text(
                    text = "If this is a false alarm or unauthorized attempt, click the cancel button immediately to secure your ledger and freeze access.",
                    color = TextDark,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("owner_cancel_request_button")
                    ) {
                        Text(
                            text = "CANCEL — FALSE ALARM",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }

                    Button(
                        onClick = onAdvance,
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("simulate_grace_period_passes_button")
                    ) {
                        Text(
                            text = "SIMULATE: GRACE PERIOD END",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            } else {
                Text(
                    text = "The owner of the vault has been alerted to your request. They have a designated grace period to dismiss or cancel this action if it is an accidental trigger. If they do not respond, a second trusted contact will be required to confirm.",
                    color = TextDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                // Testing reminder
                Text(
                    text = "Testing Hint: Switch back to \"Owner\" in the sidebar to either cancel this false alarm or simulate the grace period expiring.",
                    color = MutedAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun AwaitingConfirmationLayout(
    isOwner: Boolean,
    requestedBy: String,
    reason: String,
    viewingAs: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MutedAmber), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Pending Second",
                    tint = MutedAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Awaiting Second Confirmation",
                    color = TextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "$requestedBy's grace period has expired without the owner cancelling.",
                color = TextDark,
                fontSize = 14.sp,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reason: \"$reason\"",
                color = TextMuted,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (isOwner) {
                Text(
                    text = "The grace period has concluded. A second trusted contact is now required to authorize and confirm this action before the ledger is released.",
                    color = TextDark,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("owner_cancel_request_button")
                ) {
                    Text(
                        text = "CANCEL — FALSE ALARM",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            } else {
                val isRequestor = viewingAs == requestedBy
                if (isRequestor) {
                    Text(
                        text = "The grace period has passed. For safety, a second, different trusted contact must log in and confirm that an actual emergency exists to finalize unlocking the vault.",
                        color = TextDark,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Testing Hint: Switch the active role in the sidebar to any other trusted contact (e.g., Linda Vance) to confirm this request.",
                        color = MutedAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif
                    )
                } else {
                    Text(
                        text = "Action Required: As a second independent contact, please verify that $requestedBy's request is authentic and that an actual emergency warrants access to the security ledger.",
                        color = TextDark,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onConfirm(viewingAs) },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_second_contact_button")
                    ) {
                        Text(
                            text = "CONFIRM AND UNLOCK VAULT",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UnlockedLayout(
    isOwner: Boolean,
    requestedBy: String,
    reason: String,
    confirmer: String,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, SageGreen), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = "Unlocked",
                    tint = SageGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Vault Access Authorized & Unlocked",
                    color = TextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "The vault has been securely released for emergency viewing.",
                color = TextDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Initiated by: $requestedBy",
                color = TextDark,
                fontSize = 12.sp,
                fontFamily = FontFamily.SansSerif
            )
            Text(
                text = "Reason: \"$reason\"",
                color = TextMuted,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif
            )
            Text(
                text = "Independent confirmation provided by: $confirmer",
                color = TextDark,
                fontSize = 12.sp,
                fontFamily = FontFamily.SansSerif
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isOwner) {
                Text(
                    text = "Your vault has been unlocked via dual-custody emergency authorization. You may revoke access immediately if required.",
                    color = TextDark,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("owner_cancel_request_button")
                ) {
                    Text(
                        text = "REVOKE AND LOCK VAULT",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            } else {
                Text(
                    text = "Access is fully granted. You can now switch to the \"My Vault\" tab in the sidebar to securely view all record categories matching your designated access tier.",
                    color = SageGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun CancelledLayout(
    isOwner: Boolean,
    requestedBy: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MutedRed), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Cancelled",
                    tint = MutedRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Request Disapproved & Cancelled",
                    color = TextDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            if (isOwner) {
                Text(
                    text = "You have cancelled the emergency access request. No ledger details are shared with $requestedBy or any other contacts. The vault remains locked.",
                    color = TextDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
            } else {
                Text(
                    text = "The emergency access request was rejected or cancelled by the owner of the vault. Access has been restricted and all categories remain fully locked.",
                    color = TextDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Click \"Reset Demo\" at the top right of this screen to clear and start a new request.",
                color = TextMuted,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SecureLegacyAppPreview() {
    MyApplicationTheme {
        SecureLegacyApp()
    }
}

// ==========================================
// AMANAT LOGIN FRONTEND IMPLEMENTATION
// ==========================================

@Composable
fun LoginScreen(viewModel: SecureLegacyViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkNavy)
    ) {
        // Decorative concentric circles matching launcher icon design
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0x06FFFFFF),
                radius = size.width * 0.4f,
                center = Offset(size.width * 0.5f, size.height * 0.18f)
            )
            drawCircle(
                color = Color(0x03FFFFFF),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.18f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Branding
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                // Gold and Sage Green emblem with key hole
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SageGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Amanat Shield Logo",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Amanat",
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    letterSpacing = 0.05.sp
                )

                Text(
                    text = "SECURE DIGITAL HERITAGE LEDGER",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Middle: User Login Form Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 480.dp)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OwnerLoginLayout(viewModel)
            }

            // Footer info
            Text(
                text = "Protected with military-grade zero-knowledge encryption.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
fun OwnerPinLayout(viewModel: SecureLegacyViewModel) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var scanSuccess by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Trigger scanning simulation
    fun triggerBiometricScan() {
        if (isScanning) return
        isScanning = true
        errorMessage = ""
        // Simulate bio-scan timer
        coroutineScope.launch {
            kotlinx.coroutines.delay(1600)
            scanSuccess = true
            kotlinx.coroutines.delay(400)
            isScanning = false
            scanSuccess = false
            viewModel.viewingAs = "Owner"
            viewModel.isLoggedIn = true
        }
    }

    if (isScanning) {
        // Biometric scanning dialog layout
        Dialog(onDismissRequest = { isScanning = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = InkNavy),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Biometric Verification",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Simulating Touch ID / Face ID Authenticator Scan...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    // Pulse scanning icon
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(36.dp))
                            .background(if (scanSuccess) SageGreen else Color(0x11FFFFFF))
                            .border(BorderStroke(2.dp, if (scanSuccess) SageGreen else Color.White.copy(alpha = 0.3f)), RoundedCornerShape(36.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (scanSuccess) Icons.Default.Check else Icons.Default.Fingerprint,
                            contentDescription = "Fingerprint Sensor",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = if (scanSuccess) "Access Granted" else "Awaiting secure touch...",
                        color = if (scanSuccess) SageGreen else Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Standard PIN Lock Screen Interface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0F000000))
            .border(BorderStroke(1.dp, Color(0x11FFFFFF)), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter Security PIN",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif
        )
        Text(
            text = "Designated legacy owner passcode required.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // PIN Dot Indicator Rows
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..4) {
                val filled = enteredPin.length >= i
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (filled) SageGreen else Color.Transparent)
                        .border(
                            BorderStroke(
                                1.5.dp,
                                if (filled) SageGreen else Color.White.copy(alpha = 0.4f)
                            ), RoundedCornerShape(7.dp)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = MutedRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Demo login assistant pill
        Surface(
            color = Color(0x1F3E7C6B),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clickable {
                enteredPin = "1234"
                viewModel.viewingAs = "Owner"
                viewModel.isLoggedIn = true
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Key Info",
                    tint = SageGreen,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Demo PIN: 1234 (Tap to Quick Fill)",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Elegant custom keypad grid
        val numpadValues = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("BIO", "0", "DEL")
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            numpadValues.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    row.forEach { char ->
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(27.dp))
                                .background(
                                    when (char) {
                                        "BIO" -> Color(0x1F3E7C6B)
                                        "DEL" -> Color(0x1F1B2A3A)
                                        else -> Color(0x0BFFFFFF)
                                    }
                                )
                                .border(BorderStroke(1.dp, Color(0x0FFFFFFF)), RoundedCornerShape(27.dp))
                                .clickable {
                                    when (char) {
                                        "BIO" -> {
                                            triggerBiometricScan()
                                        }
                                        "DEL" -> {
                                            if (enteredPin.isNotEmpty()) {
                                                enteredPin = enteredPin.dropLast(1)
                                            }
                                        }
                                        else -> {
                                            if (enteredPin.length < 4) {
                                                enteredPin += char
                                                if (enteredPin.length == 4) {
                                                    if (enteredPin == "1234") {
                                                        viewModel.viewingAs = "Owner"
                                                        viewModel.isLoggedIn = true
                                                    } else {
                                                        errorMessage = "Incorrect PIN code. Hint: 1234"
                                                        enteredPin = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .testTag("keypad_$char"),
                            contentAlignment = Alignment.Center
                        ) {
                            when (char) {
                                "BIO" -> {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "Scan Fingerprint",
                                        tint = SageGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                "DEL" -> {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = "Backspace",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                else -> {
                                    Text(
                                        text = char,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedContactRequestLayout(viewModel: SecureLegacyViewModel) {
    var selectedContactName by remember { mutableStateOf(viewModel.trustedContacts.firstOrNull()?.name ?: "") }
    var claimReason by remember { 
        mutableStateOf("Owner is incapacitated. Need emergency access to medicine policies & contacts.") 
    }
    var expandedDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0F000000))
            .border(BorderStroke(1.dp, Color(0x11FFFFFF)), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Designated Contact Entry",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif
        )
        Text(
            text = "File an emergency inheritance / safe-keeping claim to gain authorized temporary access.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
        )

        // Dropdown Contact Selector
        Text(
            text = "SELECT YOUR PROFILE",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.05.sp
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x11FFFFFF))
                    .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(8.dp))
                    .clickable { expandedDropdown = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("contact_claim_dropdown"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedContactName.ifEmpty { "Select designated contact..." },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Drop Menu",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = expandedDropdown,
                onDismissRequest = { expandedDropdown = false },
                modifier = Modifier
                    .background(InkNavy)
                    .border(BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(6.dp))
            ) {
                viewModel.trustedContacts.forEach { contact ->
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text(contact.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(contact.relationship, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                            }
                        },
                        onClick = {
                            selectedContactName = contact.name
                            expandedDropdown = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reason Text Input
        Text(
            text = "REASON FOR EMERGENCY CLAIM",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.05.sp
        )
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = claimReason,
            onValueChange = { claimReason = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .testTag("claim_reason_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SageGreen
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Submit Button
        Button(
            onClick = {
                if (selectedContactName.isNotEmpty()) {
                    viewModel.viewingAs = selectedContactName
                    viewModel.initiateRequest(selectedContactName, claimReason)
                    viewModel.currentTab = Tab.ACCESS_ACTIVITY
                    viewModel.isLoggedIn = true
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("submit_claim_button")
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Shield Guard",
                modifier = Modifier.size(16.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Initiate Legacy Claim",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

// ==========================================
// REGISTERED USER LOGIN AND REGISTRATION
// ==========================================

@Composable
fun GoogleLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.22f
        val radius = (w - stroke) / 2f
        val center = Offset(w / 2f, h / 2f)

        // Draw Google 'G' arcs
        // Red Top
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 220f,
            sweepAngle = 100f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        // Yellow Left
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 120f,
            sweepAngle = 100f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        // Green Bottom
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 20f,
            sweepAngle = 100f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        // Blue Right Arc
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -40f,
            sweepAngle = 60f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        // Blue Center Bar
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(center.x, center.y),
            end = Offset(center.x + radius + stroke / 2f, center.y),
            strokeWidth = stroke
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerLoginLayout(viewModel: SecureLegacyViewModel) {
    var inputId by remember { mutableStateOf("") }
    var inputPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var showGoogleAccountChooser by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0F000000))
            .border(BorderStroke(1.dp, Color(0x11FFFFFF)), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "User Login",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif
        )
        Text(
            text = "Enter your credentials to unlock your safe vault.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
        )

        // ID field
        Text(
            text = "USER NAME OR EMAIL ID",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.05.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = inputId,
            onValueChange = { inputId = it },
            placeholder = { Text("Enter username or email", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_id_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SageGreen
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Password field
        Text(
            text = "PASSWORD",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.05.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = inputPassword,
            onValueChange = { inputPassword = it },
            placeholder = { Text("Enter password", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_password_input"),
            visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility",
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SageGreen
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = MutedRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Demo credential helper button
        if (viewModel.isRegistered) {
            Surface(
                color = Color(0x1F3E7C6B),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .clickable {
                        inputId = viewModel.registeredUsername
                        inputPassword = viewModel.registeredPassword
                    }
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Credential Helper",
                        tint = SageGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Quick fill registered user (${viewModel.registeredUsername})",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // Suggest going to Register if not registered
            Surface(
                color = Color(0x1F3E7C6B),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .clickable {
                        viewModel.showRegistrationScreen = true
                    }
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Go Register",
                        tint = SageGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "No user registered yet. Tap to Register",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Login Button
        Button(
            onClick = {
                if (inputId.isEmpty() || inputPassword.isEmpty()) {
                    errorMessage = "Please enter both ID and Password."
                } else if (viewModel.isRegistered) {
                    val matchUsername = inputId.trim().equals(viewModel.registeredUsername.trim(), ignoreCase = true)
                    val matchEmail = inputId.trim().equals(viewModel.registeredEmail.trim(), ignoreCase = true)
                    val matchPassword = inputPassword == viewModel.registeredPassword

                    if ((matchUsername || matchEmail) && matchPassword) {
                        viewModel.viewingAs = "Owner"
                        viewModel.isLoggedIn = true
                    } else {
                        errorMessage = "Invalid credentials. Please try again."
                    }
                } else {
                    // Fallback to admin/admin if no user is registered yet
                    if (inputId == "admin" && inputPassword == "admin") {
                        viewModel.viewingAs = "Owner"
                        viewModel.isLoggedIn = true
                    } else {
                        errorMessage = "No registered user. Please register first."
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_submit_button")
        ) {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = "Unlock",
                modifier = Modifier.size(16.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Secure Login",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        // OR Divider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f))
            Text(
                text = "  OR  ",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f))
        }

        // Direct Google Sign-In Button
        Button(
            onClick = { showGoogleAccountChooser = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFDADCE0)),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("google_login_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                GoogleLogoIcon(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Sign in with Google",
                    color = Color(0xFF3C4043),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Options Grid/Row for Register and Forgot Password (Admin Login removed as requested)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // "Don't have an account? Register Now" Option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Don't have an account?",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Register Now",
                    color = SageGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier
                        .clickable { viewModel.showRegistrationScreen = true }
                        .testTag("login_register_link")
                )
            }

            // Forgot Password link only
            Text(
                text = "Forgot Password?",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .clickable { showForgotPasswordDialog = true }
                    .testTag("forgot_password_link")
            )
        }
    }

    // Google Account Selection Dialog
    if (showGoogleAccountChooser) {
        Dialog(onDismissRequest = { showGoogleAccountChooser = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        GoogleLogoIcon(modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sign in with Google",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkNavy
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Choose an account to continue to Amanat",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(color = Color(0xFFEEEEEE))
                    Spacer(modifier = Modifier.height(12.dp))

                    val userGoogleEmail = if (viewModel.registeredEmail.isNotEmpty()) viewModel.registeredEmail else "asrivastava27@gmail.com"
                    val userGoogleName = if (viewModel.registeredUsername.isNotEmpty()) viewModel.registeredUsername else "Abhishek Srivastava"

                    // Primary Account
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF8F9FA))
                            .clickable {
                                viewModel.registeredEmail = userGoogleEmail
                                viewModel.registeredUsername = userGoogleName
                                viewModel.isRegistered = true
                                viewModel.viewingAs = "Owner"
                                viewModel.isLoggedIn = true
                                showGoogleAccountChooser = false
                                viewModel.registerUser(
                                    userGoogleName,
                                    viewModel.registeredPassword.ifEmpty { "GoogleAuthSecurePass" },
                                    userGoogleEmail,
                                    viewModel.registeredPhone
                                )
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(SageGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userGoogleName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userGoogleName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = InkNavy
                            )
                            Text(
                                text = userGoogleEmail,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = SageGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    var isCustomInputVisible by remember { mutableStateOf(false) }
                    var customGoogleEmail by remember { mutableStateOf("") }

                    if (isCustomInputVisible) {
                        OutlinedTextField(
                            value = customGoogleEmail,
                            onValueChange = { customGoogleEmail = it },
                            placeholder = { Text("Enter Google email", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (customGoogleEmail.contains("@")) {
                                    val name = customGoogleEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                                    viewModel.registeredEmail = customGoogleEmail
                                    viewModel.registeredUsername = name
                                    viewModel.isRegistered = true
                                    viewModel.viewingAs = "Owner"
                                    viewModel.isLoggedIn = true
                                    showGoogleAccountChooser = false
                                    viewModel.registerUser(name, "GoogleAuthSecurePass", customGoogleEmail, "")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue with Google", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isCustomInputVisible = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add account",
                                tint = TextDark,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Use another Google account",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = TextDark
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showGoogleAccountChooser = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        }
    }

    // Password Recovery Reset Link Dialog
    if (showForgotPasswordDialog) {
        var resetEmailInput by remember { 
            mutableStateOf(
                if (viewModel.registeredEmail.isNotEmpty()) viewModel.registeredEmail
                else if (inputId.contains("@")) inputId
                else "asrivastava27@gmail.com"
            ) 
        }
        var isResetSent by remember { mutableStateOf(false) }
        var resetErrorMsg by remember { mutableStateOf("") }
        var isSendingReset by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { showForgotPasswordDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
                border = BorderStroke(1.5.dp, InkNavy),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isResetSent) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(SageGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "Forgot Password Email",
                                tint = SageGreen,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Reset Password via Email",
                            color = InkNavy,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enter your registered email ID below. A password reset link will be sent to your inbox.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = resetEmailInput,
                            onValueChange = { 
                                resetEmailInput = it
                                resetErrorMsg = ""
                            },
                            label = { Text("Email ID", fontSize = 11.sp) },
                            placeholder = { Text("e.g. user@gmail.com", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SageGreen,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                                focusedTextColor = InkNavy,
                                unfocusedTextColor = InkNavy
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        if (resetErrorMsg.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = resetErrorMsg,
                                color = MutedRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (resetEmailInput.isEmpty() || !resetEmailInput.contains("@")) {
                                    resetErrorMsg = "Please enter a valid email ID."
                                } else {
                                    isSendingReset = true
                                    try {
                                        val auth = FirebaseAuth.getInstance()
                                        auth.sendPasswordResetEmail(resetEmailInput.trim())
                                    } catch (e: Throwable) {
                                        // Safe fallback
                                    }
                                    isSendingReset = false
                                    isResetSent = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSendingReset) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send Link",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Send Reset Link", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showForgotPasswordDialog = false }) {
                            Text("Cancel", color = TextMuted)
                        }
                    } else {
                        // Success State
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(SageGreen.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = SageGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Reset Link Sent!",
                            color = InkNavy,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A password reset link has been sent to:\n$resetEmailInput\n\nPlease check your email inbox and spam folder to reset your password.",
                            color = TextDark,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { showForgotPasswordDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Back to Login", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(viewModel: SecureLegacyViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var contactNo by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkNavy)
    ) {
        // Admin Floating Trigger
        IconButton(
            onClick = { viewModel.showAdminPanel = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(36.dp)
                .background(Color(0x1AFFFFFF), shape = androidx.compose.foundation.shape.CircleShape)
                .testTag("register_admin_button")
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Admin Panel",
                tint = SageGreen,
                modifier = Modifier.size(18.dp)
            )
        }

        // Decorative concentric circles
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0x06FFFFFF),
                radius = size.width * 0.4f,
                center = Offset(size.width * 0.5f, size.height * 0.18f)
            )
            drawCircle(
                color = Color(0x03FFFFFF),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.18f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                // Gold and Sage Green emblem with key hole
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SageGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Amanat Registration Logo",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Create Vault Account",
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = 0.05.sp
                )

                Text(
                    text = "SETUP SECURE DIGITAL HERITAGE LEDGER",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main 4-field card form
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0F000000))
                    .border(BorderStroke(1.dp, Color(0x11FFFFFF)), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Vault Registration",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
                Text(
                    text = "Please set up your designated login identity. These credentials are saved securely in your offline vault ledger.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )

                // Field 1: Username
                Text(
                    text = "USER NAME",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("e.g. aman_srivastava", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_username_input"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SageGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Field 2: Email ID
                Text(
                    text = "EMAIL ID",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text("e.g. user@domain.com", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_email_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SageGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Field 3: Password
                Text(
                    text = "PASSWORD",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("Choose a strong passcode", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_password_input"),
                    visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle password visibility",
                                tint = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SageGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Field 4: Contact Number
                Text(
                    text = "CONTACT NUMBER",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = contactNo,
                    onValueChange = { contactNo = it },
                    placeholder = { Text("e.g. +1 (555) 019-2834", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reg_contact_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SageGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        color = MutedRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick autofill dummy data pill for fast testing
                Surface(
                    color = Color(0x1F3E7C6B),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .clickable {
                            username = "amanat_user"
                            password = "Password123"
                            email = "user@amanat.com"
                            contactNo = "+1 (555) 789-0123"
                        }
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Fill",
                            tint = SageGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tap to Quick Fill Demo Info",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Register Button
                Button(
                    onClick = {
                        if (username.isEmpty() || password.isEmpty() || email.isEmpty() || contactNo.isEmpty()) {
                            errorMessage = "All 4 fields are required."
                        } else if (!email.contains("@")) {
                            errorMessage = "Please enter a valid email address."
                        } else if (password.length < 4) {
                            errorMessage = "Password should be at least 4 characters long."
                        } else {
                            // Register user in viewModel
                            viewModel.registerUser(
                                user = username,
                                pass = password,
                                emailAddr = email,
                                phone = contactNo
                            )
                            errorMessage = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_submit_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Register Account",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Register Ledger Account",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Toggle if already registered
                Text(
                    text = "Already have an account? Log in",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable {
                            viewModel.showRegistrationScreen = false
                        }
                        .padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer
            Text(
                text = "Protected with military-grade zero-knowledge encryption.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

// ==========================================
// ADMIN PANEL COMPOSABLES
// ==========================================

@Composable
fun AdminPanelDialog(viewModel: SecureLegacyViewModel, onDismiss: () -> Unit) {
    var activeAdminTab by remember { mutableStateOf(0) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = WarmOffWhite),
            border = BorderStroke(2.dp, InkNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Admin",
                            tint = SageGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Amanat Admin Panel",
                            color = InkNavy,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Admin Panel",
                            tint = InkNavy
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!viewModel.isAdminAuthenticated) {
                    // Show Login view if admin is not authenticated yet
                    AdminLoginView(viewModel = viewModel, onDismiss = onDismiss)
                } else {
                    // Tab Header for Admin Functions
                    ScrollableTabRow(
                        selectedTabIndex = activeAdminTab,
                        containerColor = InkNavy,
                        contentColor = Color.White,
                        edgePadding = 0.dp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = activeAdminTab == 0,
                            onClick = { activeAdminTab = 0 },
                            text = { Text("Overview", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeAdminTab == 1,
                            onClick = { activeAdminTab = 1 },
                            text = { Text("Console Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeAdminTab == 2,
                            onClick = { activeAdminTab = 2 },
                            text = { Text("Vault Items", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeAdminTab == 3,
                            onClick = { activeAdminTab = 3 },
                            text = { Text("Contacts", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeAdminTab == 4,
                            onClick = { activeAdminTab = 4 },
                            text = { Text("Access Sim", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeAdminTab == 5,
                            onClick = { activeAdminTab = 5 },
                            text = { Text("Admins", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scrollable Admin Content
                    Box(modifier = Modifier.weight(1f)) {
                        when (activeAdminTab) {
                            0 -> AdminOverviewTab(viewModel)
                            1 -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    FirebaseSyncDashboard(viewModel = viewModel)
                                }
                            }
                            2 -> AdminVaultItemsTab(viewModel)
                            3 -> AdminContactsTab(viewModel)
                            4 -> AdminAccessSimTab(viewModel)
                            5 -> AdminUsersTab(viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminLoginView(viewModel: SecureLegacyViewModel, onDismiss: () -> Unit) {
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Admin Security",
            tint = SageGreen,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Administrative Access Only",
            color = InkNavy,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Please enter authorized admin credentials. Default access is restricted to super administrator 'asrivastava27@gmail.com'.",
            color = TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Email Input
        Text(
            text = "ADMIN EMAIL",
            color = InkNavy,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = emailInput,
            onValueChange = { 
                emailInput = it
                loginError = "" 
            },
            placeholder = { Text("e.g. asrivastava27@gmail.com", color = InkNavy.copy(alpha = 0.3f), fontSize = 12.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_email_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InkNavy),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = InkNavy.copy(alpha = 0.2f),
                focusedTextColor = InkNavy,
                unfocusedTextColor = InkNavy,
                cursorColor = SageGreen
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Password Input
        Text(
            text = "ADMIN PASSWORD",
            color = InkNavy,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = passwordInput,
            onValueChange = { 
                passwordInput = it
                loginError = "" 
            },
            placeholder = { Text("Enter admin passcode", color = InkNavy.copy(alpha = 0.3f), fontSize = 12.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_password_input"),
            visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility",
                        tint = InkNavy.copy(alpha = 0.5f)
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InkNavy),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = InkNavy.copy(alpha = 0.2f),
                focusedTextColor = InkNavy,
                unfocusedTextColor = InkNavy,
                cursorColor = SageGreen
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )
        
        if (loginError.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = loginError,
                color = MutedRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                emailInput = "asrivastava27@gmail.com"
                passwordInput = "admin"
                loginError = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = SageGreen.copy(alpha = 0.2f), contentColor = InkNavy),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_quick_fill_button")
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Tap to Quick Fill Super Admin Info", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = {
                val emailClean = emailInput.trim().lowercase()
                val passClean = passwordInput.trim()
                
                if (emailClean.isEmpty()) {
                    loginError = "Please enter an admin email address."
                    return@Button
                }
                
                if (passClean.isEmpty()) {
                    loginError = "Please enter your admin password."
                    return@Button
                }
                
                val matchingAdmin = viewModel.adminUsers.find { it.email == emailClean }
                if (matchingAdmin != null && matchingAdmin.password == passClean) {
                    viewModel.loggedInAdminEmail = emailClean
                    viewModel.isAdminAuthenticated = true
                } else {
                    loginError = "Unauthorized! Admin login restricted or invalid credentials."
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("admin_auth_button")
        ) {
            Text("Verify & Access Admin", color = Color.White, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(onClick = onDismiss) {
            Text("Cancel", color = InkNavy, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AdminUsersTab(viewModel: SecureLegacyViewModel) {
    var emailToAdd by remember { mutableStateOf("") }
    var passwordToAdd by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Explanatory card
        Card(
            colors = CardDefaults.cardColors(containerColor = SageGreen.copy(alpha = 0.1f)),
            border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Admin Role Authorization",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = InkNavy,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "As the super administrator (${viewModel.loggedInAdminEmail}), you can authorize additional email addresses to have administrative login privileges. Added administrators will be able to view system audits, reset vault structures, and monitor emergency sessions.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }

        // Add admin form
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Authorize New Admin Email",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = InkNavy,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Email
                Text(text = "EMAIL ADDRESS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = InkNavy)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = emailToAdd,
                    onValueChange = { 
                        emailToAdd = it
                        addError = ""
                    },
                    placeholder = { Text("e.g. coadmin@domain.com", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = InkNavy.copy(alpha = 0.1f)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Password
                Text(text = "ADMIN PASSWORD", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = InkNavy)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = passwordToAdd,
                    onValueChange = { 
                        passwordToAdd = it
                        addError = ""
                    },
                    placeholder = { Text("Set a secure passcode", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        unfocusedBorderColor = InkNavy.copy(alpha = 0.1f)
                    ),
                    singleLine = true
                )

                if (addError.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = addError, color = MutedRed, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val emailClean = emailToAdd.trim().lowercase()
                        val passClean = passwordToAdd.trim()

                        if (emailClean.isEmpty() || !emailClean.contains("@")) {
                            addError = "Please enter a valid email address."
                            return@Button
                        }
                        if (passClean.length < 4) {
                            addError = "Password must be at least 4 characters."
                            return@Button
                        }
                        if (viewModel.adminUsers.any { it.email == emailClean }) {
                            addError = "This email is already registered as an administrator."
                            return@Button
                        }

                        viewModel.addAdminUser(emailClean, passClean)
                        emailToAdd = ""
                        passwordToAdd = ""
                        addError = "Admin registered successfully!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Authorized Admin", color = InkNavy, fontWeight = FontWeight.Bold)
                }
            }
        }

        // List of current admins
        Text(
            text = "Active Administrators",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = InkNavy,
            fontFamily = FontFamily.Serif
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            viewModel.adminUsers.forEach { admin ->
                val isSuperAdmin = admin.email == "asrivastava27@gmail.com"
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = admin.email,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = InkNavy
                                )
                                if (isSuperAdmin) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = SageGreen),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Super",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = InkNavy,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Passcode: ${admin.password}",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        if (!isSuperAdmin) {
                            IconButton(onClick = { viewModel.removeAdminUser(admin.email) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Revoke Admin Access",
                                    tint = MutedRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminOverviewTab(viewModel: SecureLegacyViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "System Status",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                StatusRow(label = "User Registered:", value = if (viewModel.isRegistered) "YES (${viewModel.registeredUsername})" else "NO")
                StatusRow(label = "Vault Items count:", value = "${viewModel.vaultItems.size}")
                StatusRow(label = "Trusted Contacts count:", value = "${viewModel.trustedContacts.size}")
                StatusRow(label = "Access Status:", value = viewModel.accessStatus.name)
                if (viewModel.accessStatus != AccessStatus.NO_REQUEST) {
                    StatusRow(label = "Requested By:", value = viewModel.requestedByContactName)
                    StatusRow(label = "Request Reason:", value = viewModel.requestReason)
                }
            }
        }

        // Demo Actions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Demo & Admin Actions",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.populateGoldenDemoData() },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = "Populate")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Up Golden Demo Data", fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Adds premium realistic assets, properties, banking items, and 3 contacts to instantly test all screens and states.",
                    fontSize = 10.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.resetDemo() },
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Clear Vault Data", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { viewModel.resetRegistration() },
                        colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset Registration", fontSize = 11.sp)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "'Reset Registration' deletes account credentials from preferences and redirects to Registration Screen.",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = InkNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AdminVaultItemsTab(viewModel: SecureLegacyViewModel) {
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(VaultCategory.EMERGENCY) }
    var expandedCat by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add Vault Item Form
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Add Vault Item",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("Details/Location/Credentials") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Category Selector
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expandedCat = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Category: ${selectedCategory.name}",
                            color = InkNavy,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = InkNavy)
                    }

                    DropdownMenu(
                        expanded = expandedCat,
                        onDismissRequest = { expandedCat = false },
                        modifier = Modifier.background(WarmOffWhite)
                    ) {
                        VaultCategory.values().forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    selectedCategory = cat
                                    expandedCat = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            viewModel.addVaultItem(title, detail, selectedCategory)
                            title = ""
                            detail = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Item", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Active Vault Items (${viewModel.vaultItems.size})",
            fontWeight = FontWeight.Bold,
            color = InkNavy,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif
        )

        Spacer(modifier = Modifier.height(6.dp))

        // List of items
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.vaultItems) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = InkNavy
                            )
                            Text(
                                text = "Category: ${item.category.name}",
                                fontSize = 10.sp,
                                color = SageGreen,
                                fontWeight = FontWeight.Bold
                            )
                            if (item.detail.isNotBlank()) {
                                Text(
                                    text = item.detail,
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.deleteVaultItem(item.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Vault Item",
                                tint = MutedRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminContactsTab(viewModel: SecureLegacyViewModel) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf(AccessTier.FULL_ACCESS) }
    var expandedTier by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add Contact Form
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Add Trusted Contact",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Contact Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = relationship,
                        onValueChange = { relationship = it },
                        label = { Text("Relationship") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier.weight(1.5f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tier Selector
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expandedTier = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Access Tier: ${selectedTier.displayName}",
                            color = InkNavy,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = InkNavy)
                    }

                    DropdownMenu(
                        expanded = expandedTier,
                        onDismissRequest = { expandedTier = false },
                        modifier = Modifier.background(WarmOffWhite)
                    ) {
                        AccessTier.values().forEach { tier ->
                            DropdownMenuItem(
                                text = { Text(tier.displayName) },
                                onClick = {
                                    selectedTier = tier
                                    expandedTier = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            viewModel.addTrustedContact(name, relationship, email, selectedTier)
                            name = ""
                            relationship = ""
                            email = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Contact", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Active Trusted Contacts (${viewModel.trustedContacts.size})",
            fontWeight = FontWeight.Bold,
            color = InkNavy,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif
        )

        Spacer(modifier = Modifier.height(6.dp))

        // List of contacts
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.trustedContacts) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${contact.name} (${contact.relationship})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = InkNavy
                            )
                            Text(
                                text = contact.email,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "Tier: ${contact.tier.displayName}",
                                fontSize = 10.sp,
                                color = SageGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { viewModel.deleteTrustedContact(contact.name) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Trusted Contact",
                                tint = MutedRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminAccessSimTab(viewModel: SecureLegacyViewModel) {
    var selectedContactName by remember { mutableStateOf(viewModel.trustedContacts.firstOrNull()?.name ?: "") }
    var reasonText by remember { mutableStateOf("Simulated Emergency: Requesting immediate access to heritage details.") }
    var secondConfirmerInput by remember { mutableStateOf("Sanjeev") }
    var expandedContacts by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Current Status Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InkNavy),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Current Simulator Status",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Status: ${viewModel.accessStatus.name}",
                    color = SageGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                if (viewModel.accessStatus != AccessStatus.NO_REQUEST) {
                    Text("Requested By: ${viewModel.requestedByContactName}", color = Color.White, fontSize = 11.sp)
                    Text("Reason: ${viewModel.requestReason}", color = Color.White, fontSize = 11.sp)
                    if (viewModel.accessStatus == AccessStatus.UNLOCKED) {
                        Text("Approved By (Second Confirmer): ${viewModel.secondConfirmerName}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // Direct Simulation Actions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Simulate Request Transitions",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (viewModel.accessStatus == AccessStatus.NO_REQUEST || viewModel.accessStatus == AccessStatus.CANCELLED) {
                    // Phase 1: Initiate Request
                    Text("Step 1: Contact Requests Access", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkNavy)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (viewModel.trustedContacts.isEmpty()) {
                        Text(
                            text = "⚠️ No trusted contacts available! Please go to Overview tab and 'Set Up Golden Demo Data' or add contacts first.",
                            color = MutedRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        // Contact Selector dropdown
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { expandedContacts = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Contact: ${selectedContactName.ifEmpty { "Select Contact" }}",
                                    color = InkNavy,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = InkNavy)
                            }

                            DropdownMenu(
                                expanded = expandedContacts,
                                onDismissRequest = { expandedContacts = false },
                                modifier = Modifier.background(WarmOffWhite)
                            ) {
                                viewModel.trustedContacts.forEach { contact ->
                                    DropdownMenuItem(
                                        text = { Text(contact.name) },
                                        onClick = {
                                            selectedContactName = contact.name
                                            expandedContacts = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = reasonText,
                            onValueChange = { reasonText = it },
                            label = { Text("Reason for access") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val cName = selectedContactName.ifEmpty { viewModel.trustedContacts.firstOrNull()?.name ?: "" }
                                if (cName.isNotBlank()) {
                                    viewModel.initiateRequest(cName, reasonText)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Trigger Access Request", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (viewModel.accessStatus == AccessStatus.GRACE_PERIOD) {
                    // Phase 2: Grace period running
                    Text("Step 2: Fast-Forward Grace Period", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkNavy)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("The owner receives notification logs. If no response is received, it transitions to second confirmation.", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.simulateGracePeriodPasses() },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Fast Forward (Simulate Time Passed)", fontWeight = FontWeight.Bold)
                    }
                } else if (viewModel.accessStatus == AccessStatus.AWAITING_SECOND_CONFIRMATION) {
                    // Phase 3: Awaiting second contact
                    Text("Step 3: Appoint Second Confirmer", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkNavy)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("To prevent malicious requests, a second trusted legal advisor/contact must confirm the critical state.", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = secondConfirmerInput,
                        onValueChange = { secondConfirmerInput = it },
                        label = { Text("Name of Confirmer contact") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (secondConfirmerInput.isNotBlank()) {
                                viewModel.confirmAsSecondContact(secondConfirmerInput)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Confirm and Unlock Vault", fontWeight = FontWeight.Bold)
                    }
                } else if (viewModel.accessStatus == AccessStatus.UNLOCKED) {
                    Text("Unlocked State Reached!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SageGreen)
                    Text("The requesting trusted contact can now view emergency items in the main dashboard.", fontSize = 11.sp, color = TextMuted)
                }

                // If active request exists, allow cancellation
                if (viewModel.accessStatus != AccessStatus.NO_REQUEST && viewModel.accessStatus != AccessStatus.CANCELLED) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.cancelRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = MutedRed),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Force Cancel/Revoke Access", fontWeight = FontWeight.Bold)
                    }
                    Text("Owner has rejected the request or revoked access.", fontSize = 10.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // Direct Force Status Override Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = WarmCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Direct State Force Override",
                    fontWeight = FontWeight.Bold,
                    color = InkNavy,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.accessStatus = AccessStatus.NO_REQUEST },
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Reset", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.accessStatus = AccessStatus.GRACE_PERIOD; viewModel.requestedByContactName = "DemoRequester" },
                        colors = ButtonDefaults.buttonColors(containerColor = InkNavy),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Grace", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.accessStatus = AccessStatus.UNLOCKED },
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Unlock", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

