package com.lomo.demo.audio;

import com.lomo.demo.BuildConfig;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;

public class SpeechTranscriptionClientManualTest {
    @Test
    public void transcribeSampleWavWithOpenAi() throws Exception {
        File audioFile = findSampleWav();
        SpeechTranscriptionClient client = new SpeechTranscriptionClient();

        String transcript = client.transcribeWithOpenAi(
                audioFile,
                BuildConfig.OPENAI_API_KEY,
                BuildConfig.OPENAI_TRANSCRIBE_MODEL
        );

        assertFalse("OpenAI transcript is empty", transcript == null || transcript.trim().isEmpty());
        System.out.println("OpenAI transcript: " + transcript);
    }

    @Test
    public void transcribeSampleWavWithGemini() throws Exception {
        File audioFile = findSampleWav();
        SpeechTranscriptionClient client = new SpeechTranscriptionClient();

        String transcript = client.transcribeWithGemini(
                audioFile,
                BuildConfig.GEMINI_API_KEY,
                BuildConfig.GEMINI_TRANSCRIBE_MODEL
        );

        assertFalse("Gemini transcript is empty", transcript == null || transcript.trim().isEmpty());
        System.out.println("Gemini transcript: " + transcript);
    }

    private static File findSampleWav() {
        File current = new File(System.getProperty("user.dir"));
        while (current != null) {
            File artifactsDir = new File(current, "artifacts");
            File[] wavFiles = artifactsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".wav"));
            if (wavFiles != null && wavFiles.length > 0) {
                return wavFiles[0];
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException("No sample wav found under artifacts/");
    }
}
