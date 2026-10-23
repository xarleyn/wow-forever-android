#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>

/* Read-only loader probe. No authentication, registry writes, network or client patching. */
static HANDLE output;
#pragma function(memset)
void *memset(void *destination, int value, size_t count) {
    volatile unsigned char *p=destination;
    while(count--) *p++=(unsigned char)value;
    return destination;
}
static void text(const char *s) { DWORD n; WriteFile(output, s, lstrlenA(s), &n, 0); FlushFileBuffers(output); }
static void wide(const WCHAR *s) {
    static char b[4096];
    if (!s) { text("(null)"); return; }
    int n = WideCharToMultiByte(CP_UTF8, 0, s, -1, b, sizeof(b), 0, 0);
    text(n ? b : "(conversion failed)");
}
static void number(DWORD value) {
    char b[12]; int n=0;
    do { b[n++]=(char)('0'+value%10); value/=10; } while(value);
    while(n) { char c[2]={b[--n],0}; text(c); }
}
static void result(const char *what, DWORD error) { text(what); text(" error="); number(error); text("\r\n"); }
static void library(const WCHAR *name) {
    text("LoadLibrary "); wide(name);
    SetLastError(0);
    HMODULE m = LoadLibraryW(name);
    DWORD error = m ? 0 : GetLastError();
    text(" error="); number(error);
    if (m) { WCHAR path[1024]; GetModuleFileNameW(m,path,1024); text(" module="); wide(path); FreeLibrary(m); }
    text("\r\n");
}
static void redirection(void) {
    ACTCTX_SECTION_KEYED_DATA data;
    ZeroMemory(&data,sizeof(data)); data.cbSize=sizeof(data);
    if (!FindActCtxSectionStringW(FIND_ACTCTX_SECTION_KEY_RETURN_HACTCTX,0,
                                 ACTIVATION_CONTEXT_SECTION_DLL_REDIRECTION,L"COMCTL32.dll",&data)) {
        result("FindActCtxSectionString",GetLastError()); return;
    }
    static BYTE buffer[8192]; SIZE_T needed=0;
    if (QueryActCtxW(0,data.hActCtx,&data.ulAssemblyRosterIndex,AssemblyDetailedInformationInActivationContext,
                    buffer,sizeof(buffer),&needed)) {
        ACTIVATION_CONTEXT_ASSEMBLY_DETAILED_INFORMATION *i=(void*)buffer;
        text("assembly="); wide(i->lpAssemblyDirectoryName);
        text(" manifest="); wide(i->lpAssemblyManifestPath); text("\r\n");
    } else result("QueryActCtx",GetLastError());
    ReleaseActCtx(data.hActCtx);
}
static void check(const WCHAR *manifest, BOOL forceX86) {
    HANDLE ctx=INVALID_HANDLE_VALUE; ULONG_PTR cookie=0;
    if (manifest) {
        text("CONTEXT "); wide(manifest); text(forceX86 ? " forced-x86\r\n" : " default-architecture\r\n");
        ACTCTXW spec; ZeroMemory(&spec,sizeof(spec)); spec.cbSize=sizeof(spec); spec.lpSource=manifest;
        if(forceX86) { spec.dwFlags=ACTCTX_FLAG_PROCESSOR_ARCHITECTURE_VALID; spec.wProcessorArchitecture=PROCESSOR_ARCHITECTURE_INTEL; }
        ctx=CreateActCtxW(&spec);
        if(ctx==INVALID_HANDLE_VALUE) { result("CreateActCtx",GetLastError()); return; }
        if(!ActivateActCtx(ctx,&cookie)) { result("ActivateActCtx",GetLastError()); ReleaseActCtx(ctx); return; }
        redirection();
    } else { text("CONTEXT executable-manifest (Battle.net-style wildcard)\r\n"); redirection(); }
    library(L"COMCTL32.dll"); library(L"comctl32.dll");
    if(cookie) DeactivateActCtx(0,cookie);
    if(ctx!=INVALID_HANDLE_VALUE) ReleaseActCtx(ctx);
}
static void manifest(const WCHAR *path, BOOL x86) {
    const char *a="<?xml version=\"1.0\"?><assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\"><assemblyIdentity type=\"win32\" name=\"WoWForever.LoaderProbe\" version=\"1.0.0.0\" processorArchitecture=\"x86\"/><dependency><dependentAssembly><assemblyIdentity type=\"win32\" name=\"Microsoft.Windows.Common-Controls\" version=\"6.0.0.0\" publicKeyToken=\"6595b64144ccf1df\" language=\"*\" processorArchitecture=\"";
    const char *b="\"/></dependentAssembly></dependency></assembly>";
    HANDLE f=CreateFileW(path,GENERIC_WRITE,FILE_SHARE_READ,0,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,0);
    if(f==INVALID_HANDLE_VALUE) { result("Write probe manifest",GetLastError()); return; }
    DWORD n; WriteFile(f,a,lstrlenA(a),&n,0); WriteFile(f,x86?"x86":"*",x86?3:1,&n,0); WriteFile(f,b,lstrlenA(b),&n,0); CloseHandle(f);
}
static void overrides(void) {
    WCHAR buffer[1024];
    DWORD count=GetEnvironmentVariableW(L"WINEDLLOVERRIDES",buffer,1024);
    text("WINEDLLOVERRIDES="); wide(count && count<1024 ? buffer : L"(absent or oversized)"); text("\r\n");
    HMODULE advapi=LoadLibraryW(L"advapi32.dll");
    typedef LSTATUS (WINAPI *RegRead)(HKEY,LPCWSTR,LPCWSTR,DWORD,LPDWORD,PVOID,LPDWORD);
    RegRead read=advapi ? (RegRead)GetProcAddress(advapi,"RegGetValueW") : 0;
    if(read) {
        const WCHAR *keys[]={L"Software\\Wine\\DllOverrides",L"Software\\Wine\\AppDefaults\\Battle.net.exe\\DllOverrides",L"Software\\Wine\\AppDefaults\\Battle.net Launcher.exe\\DllOverrides"};
        for(int i=0;i<3;i++) {
            DWORD bytes=sizeof(buffer);
            LSTATUS error=read(HKEY_CURRENT_USER,keys[i],L"comctl32",RRF_RT_REG_SZ,0,buffer,&bytes);
            text("OVERRIDE "); wide(keys[i]); text(" comctl32 error="); number((DWORD)error);
            if(!error) { text(" value="); wide(buffer); }
            text("\r\n");
        }
    }
    if(advapi) FreeLibrary(advapi);
}

/* A fixed, non-secret canary checks DPAPI across launches without reading any client data. */
static void protection(const WCHAR *directory) {
    static WCHAR path[1200]; static BYTE encrypted[4096];
    static const BYTE canary[]="WoW Forever diagnostic persistence v1";
    typedef BOOL (WINAPI *ProtectFn)(DATA_BLOB*,LPCWSTR,DATA_BLOB*,PVOID,CRYPTPROTECT_PROMPTSTRUCT*,DWORD,DATA_BLOB*);
    typedef BOOL (WINAPI *UnprotectFn)(DATA_BLOB*,LPWSTR*,DATA_BLOB*,PVOID,CRYPTPROTECT_PROMPTSTRUCT*,DWORD,DATA_BLOB*);
    HMODULE crypt=LoadLibraryW(L"crypt32.dll");
    if(!crypt) { result("DPAPI crypt32",GetLastError()); return; }
    ProtectFn protect=(ProtectFn)GetProcAddress(crypt,"CryptProtectData");
    UnprotectFn unprotect=(UnprotectFn)GetProcAddress(crypt,"CryptUnprotectData");
    if(!protect || !unprotect) { text("DPAPI functions unavailable\r\n"); FreeLibrary(crypt); return; }
    lstrcpyW(path,directory); lstrcatW(path,L"probe-dpapi-canary.bin");
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,0,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,0);
    DATA_BLOB blob={0}, decoded={0}; BOOL allocated=FALSE;
    if(file!=INVALID_HANDLE_VALUE) {
        DWORD size=GetFileSize(file,0), got=0;
        if(!size || size>sizeof(encrypted) || !ReadFile(file,encrypted,size,&got,0) || got!=size) {
            text("DPAPI existing diagnostic blob unreadable\r\n"); CloseHandle(file); FreeLibrary(crypt); return;
        }
        CloseHandle(file); blob.cbData=size; blob.pbData=encrypted;
        text("DPAPI previous-launch ");
    } else {
        DWORD error=GetLastError();
        if(error!=ERROR_FILE_NOT_FOUND) { result("DPAPI open diagnostic blob",error); FreeLibrary(crypt); return; }
        DATA_BLOB plain={sizeof(canary),(BYTE*)canary};
        if(!protect(&plain,L"WoW Forever test data",0,0,0,CRYPTPROTECT_UI_FORBIDDEN,&blob)) {
            result("DPAPI CryptProtectData",GetLastError()); FreeLibrary(crypt); return;
        }
        allocated=TRUE;
        file=CreateFileW(path,GENERIC_WRITE,FILE_SHARE_READ,0,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,0);
        if(file==INVALID_HANDLE_VALUE) result("DPAPI save diagnostic blob",GetLastError());
        else {
            DWORD written=0;
            if(!WriteFile(file,blob.pbData,blob.cbData,&written,0) || written!=blob.cbData) text("DPAPI save incomplete\r\n");
            FlushFileBuffers(file); CloseHandle(file);
        }
        text("DPAPI first-launch ");
    }
    if(!unprotect(&blob,0,0,0,0,CRYPTPROTECT_UI_FORBIDDEN,&decoded)) result("CryptUnprotectData",GetLastError());
    else {
        BOOL matches=decoded.cbData==sizeof(canary);
        if(matches) for(DWORD i=0;i<sizeof(canary);i++) if(decoded.pbData[i]!=canary[i]) matches=FALSE;
        text(matches ? "round-trip OK\r\n" : "round-trip MISMATCH\r\n");
        LocalFree(decoded.pbData);
    }
    if(allocated) LocalFree(blob.pbData);
    FreeLibrary(crypt);
}
void mainCRTStartup(void) {
    static WCHAR directory[1024], path[1200], wildcard[1200], x86[1200];
    GetModuleFileNameW(0,directory,1024);
    int n=lstrlenW(directory); while(n && directory[n-1]!='\\') --n; directory[n]=0;
    lstrcpyW(path,directory); lstrcatW(path,L"battlenet_native_probe.txt");
    output=CreateFileW(path,GENERIC_WRITE,FILE_SHARE_READ,0,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,0);
    if(output==INVALID_HANDLE_VALUE) ExitProcess(2);
    text("WoW Forever native loader probe v2 (x86 process)\r\n");
    overrides();
    typedef BOOL (WINAPI *Wow64Fn)(HANDLE,USHORT*,USHORT*);
    Wow64Fn wow=(Wow64Fn)GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"IsWow64Process2");
    if(wow) { USHORT p=0,h=0; if(wow(GetCurrentProcess(),&p,&h)) { text("processMachine="); number(p); text(" nativeMachine="); number(h); text("\r\n"); } }
    check(0,FALSE);
    lstrcpyW(wildcard,directory); lstrcatW(wildcard,L"probe-wildcard.manifest"); manifest(wildcard,FALSE);
    lstrcpyW(x86,directory); lstrcatW(x86,L"probe-x86.manifest"); manifest(x86,TRUE);
    check(wildcard,FALSE); check(x86,FALSE); check(wildcard,TRUE);
    protection(directory);
    text("PROBE_COMPLETE\r\n"); CloseHandle(output); ExitProcess(0);
}
