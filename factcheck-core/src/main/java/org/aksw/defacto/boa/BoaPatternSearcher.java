package org.aksw.defacto.boa;

import org.aksw.defacto.Defacto;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.BinaryRequestWriter;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import java.io.IOException;
import java.util.*;

/**
 * @author Daniel Gerber <dgerber@informatik.uni-leipzig.de>
 */
public class BoaPatternSearcher {


    private Logger logger = Logger.getLogger(BoaPatternSearcher.class);
    private Map<String, QueryResponse> queryCache = new HashMap<>();
    /*public static HttpSolrServer enIndex;
    public static HttpSolrServer deIndex;
    public static HttpSolrServer frIndex;*/


    public BoaPatternSearcher() {

    }

    public static void init() {


        Defacto.enIndex = new HttpSolrClient(Defacto.DEFACTO_CONFIG.getStringSetting("crawl", "solr_boa_en"));
        Defacto.enIndex.setRequestWriter(new BinaryRequestWriter());
        Defacto.deIndex = new HttpSolrClient(Defacto.DEFACTO_CONFIG.getStringSetting("crawl", "solr_boa_de"));
        Defacto.deIndex.setRequestWriter(new BinaryRequestWriter());
        Defacto.frIndex = new HttpSolrClient(Defacto.DEFACTO_CONFIG.getStringSetting("crawl", "solr_boa_fr"));
        Defacto.frIndex.setRequestWriter(new BinaryRequestWriter());
    }

    /**
     * Queries the configured BOA index with the configured number of returned
     * BOA Patterns (see Constants.NUMBER_OF_BOA_PATTERNS) and a pattern score
     * threshold of 0.5.
     *
     * @param propertyUri
     * @param language
     * @return
     */
    public List<Pattern> getNaturalLanguageRepresentations(String propertyUri, String language) {

        return querySolrIndex(propertyUri,
                50,
                Defacto.DEFACTO_CONFIG.getDoubleSetting("boa", "PATTERN_SCORE_THRESHOLD"), language);
    }

    /**
     * Queries the configured BOA index with the configured number of returned
     * BOA Patterns (see. Constants.NUMBER_OF_BOA_PATTERNS).
     *
     * @param propertyUri
     * @param numberOfBoaPatterns
     * @return
     */
    public List<Pattern> getNaturalLanguageRepresentations(String propertyUri, int numberOfBoaPatterns, String language) {

        return querySolrIndex(propertyUri, numberOfBoaPatterns, 0.5D, language);
    }

    /**
     * @param propertyUri
     * @param numberOfBoaPatterns
     * @param patternThreshold
     * @return
     */
    public List<Pattern> getNaturalLanguageRepresentations(String propertyUri, int numberOfBoaPatterns, double patternThreshold, String language) {

        return querySolrIndex(propertyUri, numberOfBoaPatterns, patternThreshold, language);
    }

    /**
     * Returns all patterns from the index and their factFeatures for reverb and the
     * wordnet distance and the overall boa-boaScore.
     *
     * @param propertyUri
     * @param language
     * @return a list of patterns
     */
    public List<Pattern> querySolrIndex(String propertyUri, int numberOfBoaPatterns, double scoreThreshold, String language) {


        this.logger.debug("Querying solr index for uri: " + propertyUri + " and language " + language + ".");

        Map<String, Pattern> patterns = new HashMap<String, Pattern>();

        try {

            if (propertyUri.equals("http://dbpedia.org/ontology/office"))
                propertyUri = "http://dbpedia.org/ontology/leaderName";

            SolrQuery query = new SolrQuery("uri:\"" + propertyUri + "\"");
            query.addField("boa-score");
            query.addField("nlr-var");
            query.addField("nlr-gen");
            query.addField("nlr-no-var");
            query.addField("SUPPORT_NUMBER_OF_PAIRS_LEARNED_FROM");
            query.addSort("SUPPORT_NUMBER_OF_PAIRS_LEARNED_FROM", SolrQuery.ORDER.desc);
            //query.addSortField("boa-score", ORDER.desc);
            if (numberOfBoaPatterns > 0) query.setRows(numberOfBoaPatterns);

            String key = propertyUri + numberOfBoaPatterns + language;

            if (!this.queryCache.containsKey(key)) {

                switch (language) {
                    case "en":
                        try {
                            this.queryCache.put(key, Defacto.enIndex.query(query));

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    case "de":
                        try {
                            this.queryCache.put(key, Defacto.deIndex.query(query));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    case "fr":
                        try {
                            this.queryCache.put(key, Defacto.frIndex.query(query));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                }
            }

            SolrDocumentList docList = this.queryCache.get(key).getResults();

            // return the first list of types
            for (SolrDocument d : docList) {

                Pattern pattern = new Pattern();
                pattern.naturalLanguageRepresentation = (String) d.get("nlr-var");
                pattern.generalized = (String) d.get("nlr-gen");
                pattern.naturalLanguageRepresentationWithoutVariables = (String) d.get("nlr-no-var");
                pattern.posTags = (String) d.get("pos");
                pattern.boaScore = (Double) d.get("SUPPORT_NUMBER_OF_PAIRS_LEARNED_FROM");
                pattern.language = language;

                this.logger.debug("Found pattern: " + pattern.naturalLanguageRepresentation);

//                System.out.println(pattern.getNormalized());

                // only add the first pattern, we don't want to override the better scored pattern
                if (!pattern.getNormalized().trim().isEmpty() && !patterns.containsKey(pattern.getNormalized())
                        && patterns.size() < Defacto.DEFACTO_CONFIG.getIntegerSetting("boa", "NUMBER_OF_BOA_PATTERNS"))
                    patterns.put(pattern.getNormalized(), pattern);
            }
        } catch (SolrServerException e) {

            System.out.println("Could not execute query: " + e);
            e.printStackTrace();
        }

        List<Pattern> patternList = new ArrayList<Pattern>(patterns.values());

        // we need to sort this list because we always want to have the same results in the eval
        Collections.sort(patternList, new Comparator<Pattern>() {

            @Override // -1 if first is smaller
            public int compare(Pattern pattern1, Pattern pattern2) {

                double difference = pattern1.boaScore - pattern2.boaScore;
                if (difference > 0) return -1;
                if (difference < 0) return 1;

                return pattern1.naturalLanguageRepresentation.compareTo(pattern2.naturalLanguageRepresentation);
            }
        });

        return patternList;
    }

    public static void main(String[] args) {

        Defacto.init();
//        queryPatterns("http://dbpedia.org/ontology/award");
//        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/birthPlace");
//        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/deathPlace");
//        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/foundationPlace");
        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/leaderName");
        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/team");
        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/author");
//        System.out.println("--------------");
        queryPatterns("http://dbpedia.org/ontology/spouse");
//        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/starring");
//        System.out.println("--------------");
//        queryPatterns("http://dbpedia.org/ontology/subsidiary");
    }

    /**
     * @param uri
     */
    private static void queryPatterns(String uri) {

        int nr = 50;
        BoaPatternSearcher bps = new BoaPatternSearcher();
        List<Pattern> sub = new ArrayList<>();
        sub.addAll(bps.getNaturalLanguageRepresentations(uri, nr, "en"));
//        sub.addAll(bps.getNaturalLanguageRepresentations(uri, nr, "de"));
//        sub.addAll(bps.getNaturalLanguageRepresentations(uri, nr,  "fr"));

        System.out.println(uri);
        Iterator<Pattern> iterator = sub.iterator();
        while (iterator.hasNext()) {
            Pattern pattern = iterator.next();

            System.out.println(pattern.naturalLanguageRepresentation + " --- " + pattern.normalize());
        }
        System.out.println();
    }
}
