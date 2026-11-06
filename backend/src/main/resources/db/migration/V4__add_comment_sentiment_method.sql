ALTER TABLE clean_comment
    ADD COLUMN sentiment_method VARCHAR(32) NOT NULL DEFAULT 'lexicon_rule';

ALTER TABLE clean_comment
    ADD CONSTRAINT chk_clean_comment_sentiment_method
    CHECK (sentiment_method = 'lexicon_rule');
