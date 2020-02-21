
 #include <stdio.h>
 #include <stdlib.h>
 #include <string.h>
 #include <math.h>
 #include <jni.h>
 
 void createHanningWindow(double w[], int N);
 double sinc(double x);
 void createFirLpf(double fe, int J, double b[], double w[]);
 
 jstring Java_develop_tomo1139_mediacodectest_AudioResampler_resample(
     JNIEnv* env,
     jobject javaThis,
     jint _channelCount,
     jstring _inputFilePath,
     jstring _outputFilePath
 ) {
 
     // TODO: 処理の並列化
     if (_channelCount != 1 && _channelCount != 2) {
         return (*env)->NewStringUTF(env, "Channel count is invalid");
     }
 
     // 入力データ読み込み
     const char *inputFilePath = (*env)->GetStringUTFChars(env, _inputFilePath, 0);
     FILE* readFp = fopen(inputFilePath, "rb");
     if (readFp == NULL) {
         return (*env)->NewStringUTF(env, "failed fopen");
     }
     fpos_t inputDataSize = 0;
     fseek(readFp, 0L, SEEK_END);
     fgetpos(readFp, &inputDataSize);
     fseek(readFp, 0L, SEEK_SET);
     short* pInputData = (short*)calloc(1, inputDataSize);
     fread(pInputData, inputDataSize, 1, readFp);
 
     int monoInputDataSize = inputDataSize;
     if (_channelCount == 2) {
         monoInputDataSize /= 2;
     }
     short* pMonoInputData = (short*)calloc(1, monoInputDataSize);
 
     if (_channelCount == 1) {
         memcpy(pMonoInputData, pInputData, monoInputDataSize);
     } else { // stereo
         for (int i=0; i<monoInputDataSize/sizeof(short); i++) {
             pMonoInputData[i] = pInputData[i*2]/2 + pInputData[i*2+1]/2;
         }
     }
     free(pInputData);
     fclose(readFp);
 
     double edgeFrequency = 20000.0 / 48000;
     double delta = 1000.0 / 48000;
 
     int delayMachineNum = (int)(3.1 / delta + 0.5) -1;
     if (delayMachineNum % 2 == 1) {
         delayMachineNum++;
     }
 
     double* hanningWindow = calloc((delayMachineNum + 1), sizeof(double));
     createHanningWindow(hanningWindow, (delayMachineNum+1));
 
     double* firLpf = calloc((delayMachineNum + 1), sizeof(double));
     createFirLpf(edgeFrequency, delayMachineNum, firLpf, hanningWindow);
 
     short* pFilteredFileData = (short*)calloc(1, monoInputDataSize);
 
     /* フィルタリング */
     for (int n = 0; n < monoInputDataSize / sizeof(short); n++) {
         double filteredData = 0.0;
         for (int m = 0; m <= delayMachineNum; m++) {
             if (n - m >= 0) {
                 filteredData += firLpf[m] * pMonoInputData[n - m] / 32768.0;
             }
         }
         pFilteredFileData[n] = (short)(filteredData * 32768);
     }
     free(pMonoInputData);
 
     // resampling
     const char *outputFilePath = (*env)->GetStringUTFChars(env, _outputFilePath, 0);
     FILE* writeFp = fopen(outputFilePath, "wb");
     if (writeFp == NULL) {
         return (*env)->NewStringUTF(env, "failed fopen");
     }
 
     int outputDataSize = monoInputDataSize * 44.1 / 48;
     short* pOutputData = (short*)calloc(1, outputDataSize);
 
     for (int i=0; i<outputDataSize/sizeof(short); i++) {
        float targetPos = i * 48 / 44.1;
        int iTargetPos = (int) targetPos;
 
        int startData = pFilteredFileData[iTargetPos];
        int endData = 0;
        if (iTargetPos + 1 < monoInputDataSize) {
            endData = pFilteredFileData[iTargetPos+1];
        }
 
        int targetData = (endData - startData) * (targetPos - iTargetPos) + startData;
        pOutputData[i] = targetData;
     }
 
     fwrite(pOutputData, outputDataSize, 1, writeFp);
 
     free(pFilteredFileData);
     free(pOutputData);
 
     fclose(writeFp);
 
     return (*env)->NewStringUTF(env, "success");
  }
 
 
 void createHanningWindow(double w[], int N) {
   int n;
 
   if (N % 2 == 0) /* Nが偶数のとき */
   {
     for (n = 0; n < N; n++)
     {
       w[n] = 0.5 - 0.5 * cos(2.0 * M_PI * n / N);
     }
   }
   else /* Nが奇数のとき */
   {
     for (n = 0; n < N; n++)
     {
       w[n] = 0.5 - 0.5 * cos(2.0 * M_PI * (n + 0.5) / N);
     }
   }
 }
 
 void createFirLpf(double fe, int J, double b[], double w[]) {
     int m;
     int offset;
 
     offset = J / 2;
     for (m = -J / 2; m <= J / 2; m++) {
         b[offset + m] = 2.0 * fe * sinc(2.0 * M_PI * fe * m);
     }
 
     for (m = 0; m < J + 1; m++) {
         b[m] *= w[m];
     }
 }
 
 double sinc(double x) {
     double y;
 
     if (x == 0.0) {
       y = 1.0;
     } else {
       y = sin(x) / x;
     }
     return y;
 }
