#include <stdio.h>
#include <stdlib.h>
#include <jni.h>

jstring Java_develop_tomo1139_mediacodectest_AudioResampler_resample(JNIEnv* env, jobject javaThis, jstring _inputFilePath, jstring _outputFilePath) {

     const char *inputFilePath = (*env)->GetStringUTFChars(env, _inputFilePath, 0);
     FILE* readFp = fopen(inputFilePath, "rb");
     if (readFp == NULL) {
         return (*env)->NewStringUTF(env, "failed fopen");
     }

     const char *outputFilePath = (*env)->GetStringUTFChars(env, _outputFilePath, 0);
     FILE* writeFp = fopen(outputFilePath, "wb");
     if (writeFp == NULL) {
         return (*env)->NewStringUTF(env, "failed fopen");
     }

     int intData;
     short shortData;
     int dataIdx = 0;
     int lastWriteIdx = -1;

     fpos_t inputDataSize = 0;
     fseek(readFp, 0L, SEEK_END);
     fgetpos(readFp, &inputDataSize);
     fseek(readFp, 0L, SEEK_SET);

     short* pInputData = (short*)calloc(1, inputDataSize);
     fread(pInputData, inputDataSize, 1, readFp);

     int outputDataSize = inputDataSize * 44.1 / 48;
     short* pOutputData = (short*)calloc(1, outputDataSize);

     for (int i=0; i<outputDataSize/sizeof(short); i++) {
        float targetPos = i * 48 / 44.1;
        int iTargetPos = (int) targetPos;

        int startData = pInputData[iTargetPos];
        int endData = 0;
        if (iTargetPos + 1 < inputDataSize) {
            endData = pInputData[iTargetPos+1];
        }

        int targetData = (endData - startData) * (targetPos - iTargetPos) + startData;
        pOutputData[i] = targetData;
     }

     fwrite(pOutputData, outputDataSize, 1, writeFp);

     fclose(readFp);
     fclose(writeFp);

     return (*env)->NewStringUTF(env, "success");
 }