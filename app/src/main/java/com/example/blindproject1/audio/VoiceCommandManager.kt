package com.example.blindproject1.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class VoiceCommandManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsManager: TTSManager
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var commandCallback: ((String) -> Unit)? = null

    fun startListening(onCommandRecognized: (String) -> Unit) {
        if (isListening) return
        
        commandCallback = onCommandRecognized
        
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            // First, ask the user
            ttsManager.speak("어디로 안내할까요? 삐 소리 후 말씀해주세요.")
            
            // Wait briefly for TTS to start/finish (A more robust way is to use UtteranceProgressListener, but simple delay works for demo)
            Thread.sleep(1500)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "목적지를 말씀해주세요")
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        Log.e("VoiceCommand", "STT Error: $error")
                        ttsManager.speak("다시 한번 말씀해주세요.")
                        destroy()
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognizedText = matches[0]
                            commandCallback?.invoke(recognizedText)
                        }
                        destroy()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(intent)
            }
        } else {
            ttsManager.speak("이 기기에서는 음성 인식을 지원하지 않습니다.")
        }
    }
}