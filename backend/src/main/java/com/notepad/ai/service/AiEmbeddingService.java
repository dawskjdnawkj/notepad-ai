package com.notepad.ai.service;

import com.notepad.ai.dto.EmbeddingTestRequest;
import com.notepad.ai.dto.EmbeddingTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiEmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingTestResponse compare(EmbeddingTestRequest request) {
        List<float[]> vectors = embeddingModel.embed(List.of(
                request.text1(),
                request.text2(),
                request.text3()
        ));
        float[] first = vectors.get(0);
        float[] second = vectors.get(1);
        float[] third = vectors.get(2);

        return new EmbeddingTestResponse(
                first.length,
                cosineSimilarity(first, second),
                cosineSimilarity(first, third)
        );
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }

        double dotProduct = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dotProduct += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
