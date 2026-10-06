#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
typedef HRESULT (WINAPI *PFN)(LPCVOID,SIZE_T,LPCSTR,const void*,void*,LPCSTR,LPCSTR,UINT,UINT,void**,void**);
typedef struct { const void* vt; } IBlob;
static const char* msg(void* b){ if(!b) return ""; void** vt=*(void***)b; typedef LPVOID (WINAPI*GP)(void*); return (const char*)((GP)vt[3])(b); }
int main(int argc,char**argv){
  FILE*f=fopen(argv[1],"rb"); fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET); char*src=malloc(n+1); fread(src,1,n,f); src[n]=0;
  HMODULE m=LoadLibraryA("d3dcompiler_47.dll"); if(!m){puts("no d3dcompiler_47");return 2;}
  PFN c=(PFN)GetProcAddress(m,"D3DCompile");
  const char* eps[][2]={{"VSBlock","vs_5_0"},{"PSBlock","ps_5_0"},{"VSOccluder","vs_5_0"},{"VSFull","vs_5_0"},{"PSOverlay","ps_5_0"},{"PSInvert","ps_5_0"},{"PSShadow","ps_5_0"},{"PSLine","ps_5_0"}};
  int bad=0;
  for(int i=0;i<8;i++){ void*code=0,*err=0; HRESULT h=c(src,n,"shaders",0,0,eps[i][0],eps[i][1],0,0,&code,&err); printf("%s %s: %s\n",eps[i][0],eps[i][1],h>=0?"ok":"FAILED"); if(h<0){bad=1; printf("%s\n",msg(err));} }
  return bad;
}
