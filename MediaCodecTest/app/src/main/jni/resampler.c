#include <stdio.h>
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
     while (fread(&shortData, sizeof(shortData), 1, readFp)) {
         dataIdx++;
         int outputIdx = dataIdx * 44.1 / 48;
         if (outputIdx != lastWriteIdx) {
             lastWriteIdx = outputIdx;
             fwrite(&shortData, sizeof(shortData), 1, writeFp);
         }
     }

     fclose(readFp);
     fclose(writeFp);

     return (*env)->NewStringUTF(env, "success");
 }