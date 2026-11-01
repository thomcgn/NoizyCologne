package de.noizycologne.backend.media;

import java.nio.file.Path;

public interface FlyerGenreAnalyzer {
    FlyerGenreAnalysisResponse analyze(MediaAsset asset, Path imagePath);
}
