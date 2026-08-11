package com.rag.cost_efficient_rag.aspect;

import com.rag.cost_efficient_rag.dto.RagQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Spring AOP Aspect to measure and log execution latency (ms), retrieved chunk count, and token usage metrics.
 */
@Slf4j
@Aspect
@Component
public class QueryLoggingAspect {

    @Around("execution(* com.rag.cost_efficient_rag.service.RagService.query(..))")
    public Object logQueryPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long latencyMs = System.currentTimeMillis() - startTime;

        if (result instanceof RagQueryResponse response) {
            if (response.getExecutionLatencyMs() == 0) {
                response.setExecutionLatencyMs(latencyMs);
            }

            long promptTokens = response.getTokenUsage() != null ? response.getTokenUsage().getPromptTokens() : 0;
            long completionTokens = response.getTokenUsage() != null ? response.getTokenUsage().getCompletionTokens() : 0;
            long totalTokens = response.getTokenUsage() != null ? response.getTokenUsage().getTotalTokens() : 0;

            log.info("RAG Query Execution Metrics -> Latency: {} ms | Chunks Retrieved: {} | Grounded: {} | Tokens (Prompt: {}, Completion: {}, Total: {})",
                    latencyMs,
                    response.getRetrievedChunkCount(),
                    response.isGrounded(),
                    promptTokens,
                    completionTokens,
                    totalTokens);
        } else {
            log.info("RAG method execution completed in {} ms", latencyMs);
        }

        return result;
    }
}
