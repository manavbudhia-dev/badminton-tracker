# assets/

Drop your trained model here as `shot_classifier.tflite` after running
`tools/train_shot_classifier.py` on your own labeled swing data.

Empty (no `shot_classifier.tflite`) is a normal state — the app falls back
to the rule-based `ShotClassifier` automatically when this file is absent.
See README.md section 3 ("ML upgrade") for the full walkthrough.
