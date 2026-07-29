package com.vaultpass.desktop.data.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.CLSID
import com.sun.jna.platform.win32.Guid.IID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.PointerByReference
import com.vaultpass.desktop.domain.platform.FilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native Windows File Picker using COM IFileDialog.
 */
class WindowsFilePicker : FilePicker {

    companion object {
        var lastUsedDirectory: String? = null
        
        val CLSID_FileOpenDialog = CLSID("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")
        val CLSID_FileSaveDialog = CLSID("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B")
        val IID_IFileOpenDialog = IID("d57c7288-d4ad-4768-be02-9d969532d960")
        val IID_IFileSaveDialog = IID("84bccd23-5fde-4cdb-aea4-af64b83d78ab")
        val IID_IShellItem = IID("43826d1e-e718-42ee-bc55-a1e261c37bfe")
        
        const val CLSCTX_INPROC_SERVER = 1
        const val FOS_PICKFOLDERS = 0x00000020
    }

    override suspend fun showOpenFileDialog(title: String, allowedExtensions: List<String>): File? =
        withContext(Dispatchers.IO) {
            showDialog(false, title, allowedExtensions, "", false)
        }

    override suspend fun showSaveFileDialog(title: String, defaultFileName: String): File? =
        withContext(Dispatchers.IO) {
            showDialog(true, title, listOf("vpb"), defaultFileName, false)
        }

    override suspend fun showFolderPickerDialog(title: String): File? =
        withContext(Dispatchers.IO) {
            showDialog(false, title, emptyList(), "", true)
        }

    private fun showDialog(isSave: Boolean, title: String, allowedExtensions: List<String>, defaultFileName: String, isFolderPicker: Boolean): File? {
        var fileDialog: IFileDialog? = null
        var shellItem: IShellItem? = null

        val hrInit = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        
        try {
            val ppv = PointerByReference()
            val clsid = if (isSave) CLSID_FileSaveDialog else CLSID_FileOpenDialog
            val iid = if (isSave) IID_IFileSaveDialog else IID_IFileOpenDialog

            val hrCreate = Ole32.INSTANCE.CoCreateInstance(
                clsid, null, CLSCTX_INPROC_SERVER, iid, ppv
            )

            if (COMUtils.FAILED(hrCreate)) {
                return null
            }

            fileDialog = IFileDialog(ppv.value)

            // Set Folder Picker Options
            if (isFolderPicker) {
                fileDialog.SetOptions(FOS_PICKFOLDERS)
            }

            // Set title
            fileDialog.SetTitle(WString(title))

            // Set Default File Name
            if (defaultFileName.isNotEmpty()) {
                fileDialog.SetFileName(WString(defaultFileName))
            }

            // Set Filter
            if (allowedExtensions.isNotEmpty()) {
                // JNA handles arrays of structures automatically
                val firstStruct = COMDLG_FILTERSPEC()
                @Suppress("UNCHECKED_CAST")
                val filtersArray = firstStruct.toArray(allowedExtensions.size) as Array<COMDLG_FILTERSPEC>
                
                allowedExtensions.forEachIndexed { index, ext ->
                    filtersArray[index].pszName = WString("VaultPass Backup (*.$ext)")
                    filtersArray[index].pszSpec = WString("*.$ext")
                }
                
                // Write all elements in the array to native memory
                for (filter in filtersArray) {
                    filter.write()
                }
                
                fileDialog.SetFileTypes(filtersArray.size, filtersArray[0].pointer)
                fileDialog.SetDefaultExtension(WString(allowedExtensions.first()))
            }

            // Show Dialog
            val hrShow = fileDialog.Show(null)

            if (COMUtils.SUCCEEDED(hrShow)) {
                val ppsi = PointerByReference()
                val hrResult = fileDialog.GetResult(ppsi)
                if (COMUtils.SUCCEEDED(hrResult)) {
                    shellItem = IShellItem(ppsi.value)
                    val ppszName = PointerByReference()
                    val SIGDN_FILESYSPATH = -2147123200 // 0x80058000
                    
                    val hrName = shellItem.GetDisplayName(SIGDN_FILESYSPATH, ppszName)
                    if (COMUtils.SUCCEEDED(hrName)) {
                        val pathPtr = ppszName.value
                        val path = pathPtr.getWideString(0)
                        Ole32.INSTANCE.CoTaskMemFree(pathPtr)
                        
                        var resultFile = File(path)
                        if (isSave && !isFolderPicker && !resultFile.name.endsWith(".vpb", ignoreCase = true)) {
                            resultFile = File(resultFile.parentFile, "${resultFile.name}.vpb")
                        }
                        
                        lastUsedDirectory = resultFile.parent
                        return resultFile
                    }
                }
            }
            return null
        } finally {
            shellItem?.Release()
            fileDialog?.Release()
            
            // Uninitialize COM if we successfully initialized it.
            // S_OK = 0, S_FALSE = 1
            if (hrInit.toInt() == 0 || hrInit.toInt() == 1) {
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }
}

// Minimal JNA Structures & COM Interfaces for IFileDialog

@Structure.FieldOrder("pszName", "pszSpec")
open class COMDLG_FILTERSPEC : Structure() {
    @JvmField var pszName: WString? = null
    @JvmField var pszSpec: WString? = null
}

class IFileDialog(p: Pointer) : Unknown(p) {
    fun Show(hwndOwner: Pointer?): HRESULT {
        return HRESULT(this._invokeNativeInt(3, arrayOf(this.pointer, hwndOwner)))
    }
    fun SetFileTypes(cFileTypes: Int, rgFilterSpec: Pointer): HRESULT {
        return HRESULT(this._invokeNativeInt(4, arrayOf(this.pointer, cFileTypes, rgFilterSpec)))
    }
    fun SetFileName(pszName: WString): HRESULT {
        return HRESULT(this._invokeNativeInt(15, arrayOf(this.pointer, pszName)))
    }
    fun SetTitle(pszTitle: WString): HRESULT {
        return HRESULT(this._invokeNativeInt(17, arrayOf(this.pointer, pszTitle)))
    }
    fun SetOptions(fos: Int): HRESULT {
        return HRESULT(this._invokeNativeInt(9, arrayOf(this.pointer, fos)))
    }
    fun GetResult(ppsi: PointerByReference): HRESULT {
        return HRESULT(this._invokeNativeInt(20, arrayOf(this.pointer, ppsi)))
    }
    fun SetDefaultExtension(pszDefaultExtension: WString): HRESULT {
        return HRESULT(this._invokeNativeInt(22, arrayOf(this.pointer, pszDefaultExtension)))
    }
}

class IShellItem(p: Pointer) : Unknown(p) {
    fun GetDisplayName(sigdnName: Int, ppszName: PointerByReference): HRESULT {
        return HRESULT(this._invokeNativeInt(5, arrayOf(this.pointer, sigdnName, ppszName)))
    }
}
