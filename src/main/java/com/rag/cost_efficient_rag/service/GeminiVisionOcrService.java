package com.rag.cost_efficient_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiVisionOcrService {

    private final MultiModelService multiModelService;

    /**
     * Render PDF pages as images and execute Multimodal Vision OCR (with structured table and chart descriptions).
     */
    public String extractTextFromScannedPdf(byte[] pdfBytes, String filename) {
        log.info("Starting Multimodal Vision OCR extraction for scanned PDF: {}", filename);
        StringBuilder fullText = new StringBuilder();

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            log.info("PDF has {} pages to render", pageCount);

            // Limit processing to first 10 pages to prevent excessive costs on large documents
            int pagesToProcess = Math.min(pageCount, 10);
            for (int i = 0; i < pagesToProcess; i++) {
                log.info("Rendering and transcribing page {}/{}", i + 1, pagesToProcess);
                BufferedImage bim = pdfRenderer.renderImageWithDPI(i, 150);
                byte[] imageBytes = toPngBytes(bim);

                String pageContent = extractFromImage(imageBytes, "image/png");
                fullText.append("\n--- PAGE ").append(i + 1).append(" ---\n");
                fullText.append(pageContent).append("\n");
            }
            if (pageCount > 10) {
                log.warn("Scanned PDF has {} pages; truncated OCR extraction to first 10 pages for cost-efficiency", pageCount);
            }
        } catch (IOException e) {
            log.error("Error rendering PDF pages: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to perform OCR: error reading PDF pages", e);
        }

        return fullText.toString();
    }

    /**
     * Perform multimodal extraction directly from raw image bytes.
     */
    public String extractFromImage(byte[] imageBytes, String mimeType) {
        log.info("Sending image bytes (size={}) to Multimodal LLM for OCR, table, and chart extraction", imageBytes.length);

        ByteArrayResource resource = new ByteArrayResource(imageBytes);
        Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), resource);

        String systemInstruction = "You are a professional document analysis agent. " +
                "Your task is to transcribe this image page completely verbatim. " +
                "Rules:\n" +
                "1. If you find any tables, format them precisely as structured Markdown tables.\n" +
                "2. If you find any graphs, charts, diagrams, or visual summaries, extract the labels, axis values, and provide a clear description of the trend/summary.\n" +
                "3. Transcribe all text content exactly as written, preserving layouts where possible.\n" +
                "4. Output only the content of the document. Do not include introductory notes, conversational remarks, or metadata comments.";

        UserMessage userMessage = new UserMessage(systemInstruction, List.of(media));
        Prompt prompt = new Prompt(userMessage);

        try {
            ChatModel activeModel = multiModelService.getModel("OPENAI");
            ChatResponse response = activeModel.call(prompt);
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getContent();
            }
        } catch (Exception e) {
            log.error("Multimodal model call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse document page with multimodal LLM: " + e.getMessage(), e);
        }

        return "";
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }
}
