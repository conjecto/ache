package focusedCrawler.dedup;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import focusedCrawler.learn.classifier.smile.DoubleVectorizer;
import focusedCrawler.learn.classifier.smile.SmileOnlineClassifier;
import focusedCrawler.learn.classifier.smile.SmileOnlineClassifier.Learner;
import focusedCrawler.learn.vectorizer.HashingVectorizer;
import focusedCrawler.link.classifier.LinkClassifierDeduplication;

public class RunOnlineDedupClassifier {
    
    private static final int NOT_DUPLICATE = 0;
    private static final int DUPLICATE = 1;
    static int[] classes = new int[]{NOT_DUPLICATE, DUPLICATE};
    
    
    public static void main(String[] args) throws Exception {
        
        String inputPath = "/home/aeciosantos/workdata/dedup/";
        
//        String trainFile = inputPath+"spark/common-crawl_count_digest_url_top1000__part-00000.train";
//        String testFile  = inputPath+"spark/common-crawl_count_digest_url_top1000__part-00000.test";
//        boolean timestamp = false;
        
        String trainFile = inputPath + "crawleval_dups.csv.train";
        String testFile = inputPath + "crawleval_dups.csv.test";
        boolean timestamp = true;

        System.out.println("Reading training data...");
        List<DupLine> trainFileData = readInputFile(trainFile, timestamp);
        
        System.out.println("size: "+trainFileData.size());
        
        System.out.println("Grouping duplicates by content hash...");
        Map<String, List<String>> urlsByHash = groupByContentHash(trainFileData);


        
        List<String> trainingData = new ArrayList<>();
        List<Integer> trainingDataClasses = new ArrayList<>();
        
        int dupCount = 0;
        int nodupCount = 0;
        for (DupLine dup : trainFileData) {
            
            int numberOfDups = urlsByHash.get(dup.hash).size();
            int instanceClass = numberOfDups > 1 ? DUPLICATE : NOT_DUPLICATE;
            
//            double percentNoDup = nodupCount / (double) (dupCount+nodupCount);
////            System.out.printf("Percent NoDup: %.4f\n", percentNoDup);
//            if(instanceClass.equals(NOT_DUPLICATE) && percentNoDup > 0.5d) {
//                continue;
//            }
            
            if (instanceClass == DUPLICATE) {
                dupCount++;
            } else {
                nodupCount++;
            }
            
            trainingData.add(dup.url);
            trainingDataClasses.add(instanceClass);
//            System.out.println(instanceClass + " " + numberOfDups + " " + dup.url);
        }
        
        
        
        
        
        HashingVectorizer vectorizer = new HashingVectorizer(12, true);
//        BinaryTextVectorizer vectorizer = new BinaryTextVectorizer(true);
//        UrlParserVectorizer vectorizer = new UrlParserVectorizer();
        
//        for (int i = 0; i < trainingData.size(); i++) {
//            List<String> urlTokens = Sequence.parseTokens(trainingData.get(i));
//            vectorizer.partialFit(urlTokens);
//        }

        
        
//        Learner learner = Learner.SVM;
        Learner learner = Learner.GRADIENT_BOOSTING;
//        Learner learner = Learner.RANDOM_FOREST;

        String[] featuresArray = vectorizer.getFeaturesAsArray();

        DoubleVectorizer<String> wekaVectorizer = new DoubleVectorizer<String>() {
            @Override
            public double[] toInstance(String object) {
                return LinkClassifierDeduplication.convertToWekaInstance(object, vectorizer);
            }
        };
        SmileOnlineClassifier<String> classifier =
                new SmileOnlineClassifier<>(learner, featuresArray, classes, wekaVectorizer);

//        if (classifierImpl instanceof SoftClassifier) {
//            for (int i = 0; i < trainingData.size(); i++) {
//                classifier.updateModel(trainingData.get(i), trainingDataClasses.get(i));
//            }
//        } else {
            classifier.buildModel(trainingData, trainingDataClasses);
//        }


        System.out.println("Reading test data...");
        List<DupLine> testFileData = readInputFile(testFile, timestamp);
        System.out.println("Test size: " + testFileData.size());

        // Collections.shuffle(testFileData, new Random(0));

         evaluate(classifier, testFileData);

        System.out.println("Sorting by predition...");
        testFileData.sort((DupLine d1, DupLine d2) -> {
            double scoreD1 = classifier.classify(d1.url)[0];
            double scoreD2 = classifier.classify(d2.url)[0];
            System.out.println(scoreD1 + " " + scoreD2);
            return Double.compare(scoreD2, scoreD1); // reverse
        });

        System.out.println("Writring results file...");
        FileWriter f = new FileWriter("/home/aeciosantos/workdata/dedup/weka."
                + learner + ".txt");
        for (DupLine d : testFileData) {
            String actualClass = d.numOfDups > 1 ? "0" : "1";
            f.write(actualClass);
            f.write(" qid:1 1:0.0\n");
        }
        f.close();
    }

    private static void evaluate(SmileOnlineClassifier<String> classifier,
            List<DupLine> testFileData) {
        int dupCount = 0;
        int nodupCount = 0;
        int total = 0, tp = 0, fp = 0, tn = 0, fn = 0;
        for (DupLine dup : testFileData) {

            // int numberOfDups = urlsByHash.get(dup.hash).size();
            int numberOfDups = dup.numOfDups;
            int actualClass = numberOfDups > 1 ? DUPLICATE : NOT_DUPLICATE;



            //
            // Class balancing
            //
            double percentNoDup = nodupCount / (double) (dupCount + nodupCount);
            // System.out.printf("Percent NoDup: %.4f\n", percentNoDup);
            if (actualClass == NOT_DUPLICATE && percentNoDup > 0.5d) {
                continue;
            }
            if (actualClass == DUPLICATE) {
                dupCount++;
            } else {
                nodupCount++;
            }

            //
            // Metrics
            //
            double[] result = classifier.classify(dup.url);
            double score = result[0];
            int prediction = score > 0.5d ? NOT_DUPLICATE : DUPLICATE;
            // System.out.printf("%.4f %s %s\n", score, prediction, actualClass);

            if (prediction == DUPLICATE) { // is dup
                if (actualClass == DUPLICATE) {
                    tp++;
                } else {
                    fp++;
                }
            } else { // not dup
                if (actualClass == NOT_DUPLICATE) {
                    tn++;
                } else {
                    fn++;
                }
            }

            total++;
            if (total % 1000 == 0) {
                System.out.printf(
                        "precision: %.4f recall: %.4f fdr: %.4f npv: %.4f\n",
                        (tp / (double) (tp + fp)),
                        (tp / (double) (tp + fn)),
                        // (fn/(double)(tn+fn)),
                        (fp / (double) (fp + tp)),
                        (tn / (double) (tn + fn)));
            }

        }
        System.out.println("Final result:");
        System.out.printf(
                "precision: %.4f recall: %.4f\n",
                (tp / (double) (tp + fp)),
                (tp / (double) (tp + fn)));
    }

    private static Map<String, List<String>> groupByContentHash(List<DupLine> trainData) {
        Map<String, List<String>> urlsByHash = new HashMap<>();
        for (DupLine d : trainData) {
            List<String> list = urlsByHash.get(d.hash);
            if (list == null) {
                list = new ArrayList<>();
                urlsByHash.put(d.hash, list);
            }
            list.add(d.url);
        }
        return urlsByHash;
    }

    @SuppressWarnings("unused")
    private static class DupLine {
        public int numOfDups;
        public long timestamp;
        public String url;
        public String hash;
    }

    public static List<DupLine> readInputFile(String filename, boolean timestamp)
            throws IOException {

        List<DupLine> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] split = line.split(" ");

                int size = 3;
                if (timestamp) {
                    size += 1;
                }

                if (split.length != size) {
                    System.out.printf("Failed to match line: %s\n", line);
                    continue;
                }

                DupLine lineObj = new DupLine();
                int i = 0;
                lineObj.numOfDups = Integer.parseInt(split[i++]);
                lineObj.hash = split[i++];
                if (timestamp) {
                    lineObj.timestamp = Long.parseLong(split[i++]);
                }
                lineObj.url = split[i++];

                lines.add(lineObj);

            }
        }
        return lines;
    }


}