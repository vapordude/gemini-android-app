package nz.kaimahi.bridge.research

/**
 * Typed catalogue of feed sources for the daily front page. Stays
 * in-repo (not config-file) so the agent + fetcher have a single
 * authoritative list; user-editable overrides come later via
 * `filesDir/research/sources.json`.
 *
 * See `docs/research/daily-front-page.md` for the source rationale.
 */
data class FeedSource(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    val url: String,
    val topics: Set<FeedTopic>,
)

enum class SourceKind {
    ArxivRss,
    LabBlog,
    Aggregator,
    Journal,
}

enum class FeedTopic(val displayName: String) {
    All("All"),
    MachineLearning("cs.LG"),
    Language("cs.CL"),
    QuantumPhysics("quant-ph"),
    Cosmology("cosmology"),
    CogSci("cogsci"),
    Synthesis("synthesis"),
    Labs("labs"),
}

object FeedSources {

    val ARXIV_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            id = "arxiv-cs-lg",
            displayName = "arXiv cs.LG",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/cs.LG",
            topics = setOf(FeedTopic.MachineLearning),
        ),
        FeedSource(
            id = "arxiv-cs-ai",
            displayName = "arXiv cs.AI",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/cs.AI",
            topics = setOf(FeedTopic.MachineLearning),
        ),
        FeedSource(
            id = "arxiv-stat-ml",
            displayName = "arXiv stat.ML",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/stat.ML",
            topics = setOf(FeedTopic.MachineLearning),
        ),
        FeedSource(
            id = "arxiv-cs-cl",
            displayName = "arXiv cs.CL",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/cs.CL",
            topics = setOf(FeedTopic.Language),
        ),
        FeedSource(
            id = "arxiv-quant-ph",
            displayName = "arXiv quant-ph",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/quant-ph",
            topics = setOf(FeedTopic.QuantumPhysics, FeedTopic.Synthesis),
        ),
        FeedSource(
            id = "arxiv-gr-qc",
            displayName = "arXiv gr-qc",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/gr-qc",
            topics = setOf(FeedTopic.Cosmology, FeedTopic.Synthesis),
        ),
        FeedSource(
            id = "arxiv-nlin-ao",
            displayName = "arXiv nlin.AO",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/nlin.AO",
            topics = setOf(FeedTopic.CogSci, FeedTopic.Synthesis),
        ),
        FeedSource(
            id = "arxiv-q-bio-nc",
            displayName = "arXiv q-bio.NC",
            kind = SourceKind.ArxivRss,
            url = "http://export.arxiv.org/rss/q-bio.NC",
            topics = setOf(FeedTopic.CogSci, FeedTopic.Synthesis),
        ),
    )

    val LAB_FEEDS: List<FeedSource> = listOf(
        FeedSource("hf-papers", "Hugging Face Papers", SourceKind.Aggregator,
            "https://huggingface.co/papers", setOf(FeedTopic.MachineLearning, FeedTopic.Labs)),
        FeedSource("deepmind", "DeepMind", SourceKind.LabBlog,
            "https://deepmind.google/discover/blog", setOf(FeedTopic.Labs)),
        FeedSource("meta-fair", "Meta AI (FAIR)", SourceKind.LabBlog,
            "https://ai.meta.com/blog", setOf(FeedTopic.Labs)),
        FeedSource("mistral", "Mistral", SourceKind.LabBlog,
            "https://mistral.ai/news", setOf(FeedTopic.Labs)),
        FeedSource("anthropic", "Anthropic", SourceKind.LabBlog,
            "https://anthropic.com/news", setOf(FeedTopic.Labs)),
        FeedSource("xai", "xAI", SourceKind.LabBlog,
            "https://x.ai/blog", setOf(FeedTopic.Labs)),
        FeedSource("openai", "OpenAI", SourceKind.LabBlog,
            "https://openai.com/blog", setOf(FeedTopic.Labs)),
        FeedSource("eleuther", "EleutherAI", SourceKind.LabBlog,
            "https://blog.eleuther.ai", setOf(FeedTopic.Labs, FeedTopic.MachineLearning)),
        FeedSource("together", "Together AI", SourceKind.LabBlog,
            "https://together.ai/blog", setOf(FeedTopic.Labs)),
        FeedSource("laion", "LAION", SourceKind.LabBlog,
            "https://laion.ai/blog", setOf(FeedTopic.Labs)),
        FeedSource("paperswithcode", "Papers With Code", SourceKind.Aggregator,
            "https://paperswithcode.com", setOf(FeedTopic.MachineLearning)),
        FeedSource("semantic-scholar", "Semantic Scholar", SourceKind.Aggregator,
            "https://semanticscholar.org", setOf(FeedTopic.MachineLearning)),
    )

    val SYNTHESIS_FEEDS: List<FeedSource> = listOf(
        FeedSource("santa-fe", "Santa Fe Institute", SourceKind.LabBlog,
            "https://santafe.edu", setOf(FeedTopic.CogSci, FeedTopic.Synthesis)),
        FeedSource("allen", "Allen Institute (Brain)", SourceKind.LabBlog,
            "https://alleninstitute.org/news", setOf(FeedTopic.CogSci)),
        FeedSource("verses", "VERSES AI / Friston", SourceKind.LabBlog,
            "https://verses.ai/blog", setOf(FeedTopic.CogSci, FeedTopic.Synthesis)),
        FeedSource("perimeter", "Perimeter Institute", SourceKind.LabBlog,
            "https://perimeterinstitute.ca", setOf(FeedTopic.QuantumPhysics, FeedTopic.Cosmology)),
        FeedSource("templeton", "John Templeton Foundation", SourceKind.LabBlog,
            "https://templeton.org/news", setOf(FeedTopic.Synthesis)),
        FeedSource("nautilus", "Nautilus", SourceKind.Journal,
            "https://nautil.us", setOf(FeedTopic.Synthesis)),
        FeedSource("aeon", "Aeon", SourceKind.Journal,
            "https://aeon.co", setOf(FeedTopic.Synthesis)),
        FeedSource("jaic", "Journal of AI and Consciousness", SourceKind.Journal,
            "https://www.worldscientific.com/worldscinet/jaic", setOf(FeedTopic.Synthesis)),
    )

    val ALL: List<FeedSource> = ARXIV_FEEDS + LAB_FEEDS + SYNTHESIS_FEEDS

    fun byTopic(topic: FeedTopic): List<FeedSource> =
        if (topic == FeedTopic.All) ALL else ALL.filter { topic in it.topics }
}
