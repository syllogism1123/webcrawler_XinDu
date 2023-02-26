package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;


/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final List<Pattern> ignoredUrls;
    private final int maxDepth;
    private final PageParserFactory parserFactory;

    @Inject
    ParallelWebCrawler(Clock clock,
                       @Timeout Duration timeout,
                       @PopularWordCount int popularWordCount,
                       @TargetParallelism int threadCount,
                       @IgnoredUrls List<Pattern> ignoredUrls,
                       @MaxDepth int maxDepth,
                       PageParserFactory parserFactory) {
        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
        this.ignoredUrls = ignoredUrls;
        this.maxDepth = maxDepth;
        this.parserFactory = parserFactory;
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {

        Instant deadline = clock.instant().plus(timeout);
        ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
        ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
        for (String url : startingUrls) {
            pool.invoke(new Crawler(url, deadline, maxDepth, counts, visitedUrls));
        }

        if (counts.isEmpty()) {
            return new CrawlResult.Builder()
                    .setWordCounts(counts)
                    .setUrlsVisited(visitedUrls.size())
                    .build();
        }

        return new CrawlResult.Builder()
                .setWordCounts(WordCounts.sort(counts, popularWordCount))
                .setUrlsVisited(visitedUrls.size())
                .build();
    }

    public class Crawler extends RecursiveTask<Boolean> {
        private final String url;
        private final Instant deadline;
        private final int maxDepth;
        private final ConcurrentMap<String, Integer> counts;
        private final ConcurrentSkipListSet<String> visitedUrls;

        public Crawler(String url,
                       Instant deadline,
                       int maxDepth,
                       ConcurrentMap<String, Integer> counts,
                       ConcurrentSkipListSet<String> visitedUrls) {
            this.url = url;
            this.deadline = deadline;
            this.maxDepth = maxDepth;
            this.counts = counts;
            this.visitedUrls = visitedUrls;
        }

//        private boolean isIgnored(String url) {
//            return ignoredUrls
//                    .stream()
//                    .anyMatch(pattern -> pattern.matcher(url).matches());
//        }

        @Override
        protected Boolean compute() {
            Lock lock = new ReentrantLock();

            if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
                return false;
            }

            for (Pattern pattern : ignoredUrls) {
                if (pattern.matcher(url).matches()) {
                    return false;
                }
            }

            lock.lock();

            try {
                if (visitedUrls.contains(url)) {
                    return false;
                }
                visitedUrls.add(url);

            } finally {
                lock.unlock();
            }

            PageParser.Result result = parserFactory.get(url).parse();
            for (ConcurrentMap.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
                counts.compute(e.getKey(), (k, v) -> (v == null) ? e.getValue() : e.getValue() + v);
            }

            List<Crawler> subtasks = new ArrayList<>();
            for (String link : result.getLinks()) {
                subtasks.add(new Crawler(link, deadline, maxDepth - 1, counts, visitedUrls));
            }

            invokeAll(subtasks);
            return true;
        }
    }


    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }
}
