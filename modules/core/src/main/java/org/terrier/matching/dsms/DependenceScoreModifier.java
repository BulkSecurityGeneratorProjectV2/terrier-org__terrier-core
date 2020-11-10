/*
 * Terrier - Terabyte Retriever 
 * Webpage: http://terrier.org 
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is DependenceScoreModifier.java.
 *
 * The Original Code is Copyright (C) 2004-2020 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Vassilis Plachouras <vassilis{a.}dcs.gla.ac.uk> (original author)
 *   Craig Macdonald <craigm{a.}dcs.gla.ac.uk>
 *   Jie Peng <pj{a.}dcs.gla.ac.uk>
 */
package org.terrier.matching.dsms;

import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.PostingListManager;
import org.terrier.matching.ResultSet;
import org.terrier.sorting.MultiSort;
import org.terrier.structures.CollectionStatistics;
import org.terrier.structures.EntryStatistics;
import org.terrier.structures.Index;
import org.terrier.structures.Lexicon;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.Pointer;
import org.terrier.structures.postings.BlockPosting;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.structures.postings.Posting;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.Distance;

/** Base class for Dependence models. Document scores are modified using n-grams,
 * approximating the dependence of terms between documents. Implemented as a document 
 * score modifier, similarly to PhraseScoreModifier. Postings lists are traversed in a 
 * DAAT fashion.
 * <p>
 * <b>Properties</b>
 * <ul>
 * <li><tt>proximity.dependency.type</tt> - one of SD, FD for sequential dependence or full dependence</li>
 * <li><tt>proximity.ngram.length</tt> - proxmity windows, in tokens</li>
 * <li><tt>proximity.w_t</tt> - weight of unigram in combination, defaults 1.0d</li>
 * <li><tt>proximity.w_o</tt> - weight of SD in combination, default 1.0d</li>
 * <li><tt>proximity.w_u</tt> - weight of FD in combination, default 1.0d</li>
 * <li><tt>proximity.qtw.fnid</tt> - combination function to combine the qtws of
 * two terms involved in a phrase. See below.</li>
 * </ul>
 * <p>
 * <b>QTW Combination Functions</b>
 * <ol>
 * <li><tt>1</tt>: phraseQTW = 0.5 * (qtw1 + qtw2)</li>
 * <li><tt>2</tt>: phraseQTW = qtw1 * qtw2</li>
 * <li><tt>3</tt>: phraseQTW = min(qtw1, qtw2)</li>
 * <li><tt>4</tt>: phraseQTW = max(qtw1, qtw2)</li>
 * </ol>
 * 
 * @author Craig Macdonald, Vassilis Plachouras, Jie Peng
 * @since 3.0
 */
public abstract class DependenceScoreModifier  implements DocumentScoreModifier {

	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	
	/** Creates a clone of this object */ @Override 
	public Object clone() {
		try{
			return super.clone();
		} catch (Exception e) {
			throw new AssertionError();
		}
	}
	
	/** The size of the considered ngrams */
	protected int ngramLength = Integer.parseInt(ApplicationSetup.getProperty(
				"proximity.ngram.length", "2"));

	protected abstract double scoreFDSD(int matchingNGrams, int docLength);

	/** type of proximity to use */
	protected String dependency = ApplicationSetup.getProperty(
				"proximity.dependency.type", "");
	protected final int phraseQTWfnid = Integer.parseInt(ApplicationSetup
				.getProperty("proximity.qtw.fnid", "1"));
	/** weight of unigram model */
	protected double w_t = Double.parseDouble(ApplicationSetup.getProperty(
				"proximity.w_t", "1.0d"));
	/** weight of ordered dependence model */
	protected double w_o = Double.parseDouble(ApplicationSetup.getProperty(
				"proximity.w_o", "1.0d"));
	/** weight of unordered dependence model */
	protected double w_u = Double.parseDouble(ApplicationSetup.getProperty(
				"proximity.w_u", "1.0d"));
	/** A list of the strings of the phrase terms. */
	protected String[] phraseTerms;
	protected double avgDocLen = 0.0d;
	protected double numTokens;
	/**
	 * Returns the name of the modifier. 
	 * @return String the name of the modifier.
	 */
	public String getName() {
		return this.getClass().getSimpleName();
	}

	protected static boolean NOR(final boolean[] in) {
		for(boolean b : in)
		{
			if (b)
				return false;
		}
		return true;
	}

	/**
	 * Modifies the scores of documents, in which there exist, or there does not
	 * exist a given phrase.
	 * 
	 * @param index
	 *            Index the data structures to use.
	 * @param terms
	 *            MatchingQueryTerms the terms to be matched for the query. This
	 *            does not correspond to the phrase terms necessarily, but to
	 *            all the terms of the query.
	 * @param set
	 *            ResultSet the result set for the query.
	 * @return true if any scores have been altered
	 */
	public boolean modifyScores(Index index, MatchingQueryTerms terms, ResultSet set) {
		logger.info(super.toString() + " ngramlength="+ this.ngramLength);
		try {
			if (phraseQTWfnid < 1 || phraseQTWfnid > 4) {
				logger.error("Wrong function id specified for " + this.getClass().getSimpleName());
			}
	
			MatchingQueryTerms termsFiltered = terms.stream().filter(x -> ! x.getKey().toString().matches("^.*#(\\d|uw\\d|ow\\d).*")).collect(Collectors.toCollection(MatchingQueryTerms::new));
			termsFiltered.setQueryId(terms.getQueryId());
			boolean splitSynonyms = Boolean.parseBoolean(ApplicationSetup.getProperty(
				"proximity.plm.split.synonyms", "true"));
			PostingListManager plm = new PostingListManager(index, index.getCollectionStatistics(), termsFiltered, splitSynonyms, null, null);
			plm.prepare(false);
			phraseTerms = new String[plm.getNumTerms()];
			EntryStatistics[] es = new EntryStatistics[plm.getNumTerms()];
			IterablePosting[] ips = new IterablePosting[plm.getNumTerms()];
			
			for (int i = 0; i < plm.getNumTerms(); i++) {
				phraseTerms[i] = plm.getTerm(i);
				es[i] = plm.getStatistics(i);
				ips[i] = plm.getPosting(i);
			}
			
			final int phraseLength = phraseTerms.length;
			if (phraseLength == 1)
			{
				plm.close();
				return false;
			}
			
			final double[] phraseTermWeights = new double[phraseLength];
			for (int i = 0; i < phraseLength; i++) {
				phraseTermWeights[i] = terms.getTermWeight(phraseTerms[i]);
				logger.debug("phrase term: " + phraseTerms[i]);
			}
	
			
			w_t = Double.parseDouble(ApplicationSetup.getProperty(
					"proximity.w_t", "1.0d"));
			w_o = Double.parseDouble(ApplicationSetup.getProperty(
					"proximity.w_o", "1.0d"));
			w_u = Double.parseDouble(ApplicationSetup.getProperty(
					"proximity.w_u", "1.0d"));
	
			
			
			
			if (dependency.equals("FD")) {
				doDependency(index, es, ips, set, phraseTermWeights, false);
				plm.close();
			} else if (dependency.equals("SD")) {
				doDependency(index, es, ips, set, phraseTermWeights, true);
				plm.close();
			} else {
				plm.close();
				logger.error("proximity.dependency.type not set. Set it to either FD or SD");
				return false;
			}
		} catch (ClassCastException e) {
			logger.error("Error in " + this.getClass().getName() + " "
					+ e + " -- does your index have blocks (positions) enabled?", e);
		} catch (Exception e) {
			logger.error("Error", e);
		}
		// returning true, assuming that we have modified the scores of
		// documents
		return true;
	}
	
	/** unused hook method */
	protected void determineGlobalStatistics(String[] terms, EntryStatistics[] es, boolean SD) throws IOException
	{}
	
	/** Opens the posting list for an index and lexicon entry **/
	protected void openPostingLists(Index index, LexiconEntry[] les, IterablePosting[] ips) throws IOException
	{
		final Lexicon<String> lexicon = index.getLexicon();		
		for (int i = 0; i < phraseTerms.length; i++) {
			les[i] = lexicon.getLexiconEntry(phraseTerms[i]);
			if (les[i] != null) {
				ips[i] = index.getInvertedIndex().getPostings((Pointer) les[i]);
			}
		}
	}


    /** Calculates dependence scores for all documents, putting the scores into the ResultSet rs */
	protected void doDependency(Index index, final EntryStatistics es[], final IterablePosting ips[], ResultSet rs, final double[] phraseTermWeights, boolean SD) throws IOException 
	{
				
		final int numPhraseTerms = phraseTerms.length;
		final boolean[] postingListFinished = new boolean[numPhraseTerms];
		
		for(int i=0;i<numPhraseTerms;i++)
		{
			postingListFinished[i] = ips[i].next() == IterablePosting.EOL;
		}
		
		this.setCollectionStatistics(index.getCollectionStatistics(), index);
		determineGlobalStatistics(phraseTerms, es, SD);
		
		final int[] docids = rs.getDocids();
		final double[] scores = rs.getScores();
		final short[] occurrences = rs.getOccurrences();
	
		int altered = 0;
		
		// Sort by docid so that term postings can be read sequentially (ip.next())
		MultiSort.ascendingHeapSort(docids, scores, occurrences, docids.length);
	
		
		// firstly, apply w_t to all document scores
		final int docidsLength = docids.length;
		boolean allZero = true;
		for (int i = 0; i < docidsLength; i++) {
			if (scores[i] != 0.0d)
				allZero = false;
			scores[i] = w_t * scores[i];
		}
	
		// for each retrieved document
		DOC: for (int k = 0; k < docidsLength; k++) {
			// update the posting iterators to be in the correct place
			int i = -1;
			int targetDocId = docids[k];
			
			if (! allZero && scores[k] <= 0.0d)
				continue DOC;
			
			//System.err.print("docid=" + targetDocId);
			
			// ok to use is set for each term when that term has a posting for
			// the current docid
			boolean[] okToUse = new boolean[numPhraseTerms];
			TERM: for (IterablePosting ip : ips)
			{
				i++;
				if (postingListFinished[i]) {
					okToUse[i] = false;
					continue TERM;
				}
				okToUse[i] = true;
				if (ip == null) {
					okToUse[i] = false;
					continue TERM;
				}
				
				while (ip.getId() < targetDocId) {
				//do {
					if (ip.next() == IterablePosting.EOL) {
						okToUse[i] = false;
						postingListFinished[i] = true;
						continue TERM;
					}
				} //while (ip.getId() < targetDocId);
				if (ip.getId() > targetDocId) {
					// this term doesnt have it.
					okToUse[i] = false;
					continue TERM;
				}
				okToUse[i] = true;
			}
	
			if (countTrue(okToUse) < 2)
			{
				//this document will not be considered, as it has no pair of query terms present
				continue DOC;
			}
			altered++;
			// ok, all postings which have okToUse set to true, can be used in
			// prox calculation
            scores[k] += calculateDependence(ips, okToUse, phraseTermWeights, SD);
		}
	
		for (IterablePosting ip : ips) {
			if (ip != null)
				ip.close();
		}
		logger.info(this.getClass().getSimpleName() + " altered scores for " + altered + " documents");
	
	}
    
    /** calculates the dependence score for one document, using the IterablePostings available.
      * @param ips all of the IterablePostings
      * @param okToUse the IterablePostings that are set on the current document
      * @param phraseTermWeights weights on each of the query terms
      * @param SD is sequential dependence to be used
      * @return score of this dependence score modifier for the current document
      */
    protected double calculateDependence(Posting[] ips, boolean[] okToUse, double[] phraseTermWeights, boolean SD) {
        final int numPhraseTerms = phraseTerms.length;
        int i;
        double finalScore = 0.0d;
        if (SD) {
            TERM: for (i = 0; i < numPhraseTerms - 1; i++) {
                if (!okToUse[i] || !okToUse[i + 1])
                    continue TERM;
                double combinedPhraseQTWWeight;
                switch (phraseQTWfnid) {
                    case 1:
                        combinedPhraseQTWWeight = 0.5 * phraseTermWeights[i]
                        + 0.5 * phraseTermWeights[i + 1];
                        break;
                    case 2:
                        combinedPhraseQTWWeight = phraseTermWeights[i]
                        * phraseTermWeights[i + 1];
                        break;
                    case 3:
                        combinedPhraseQTWWeight = Math.min(
                                                           phraseTermWeights[i], phraseTermWeights[i + 1]);
                        break;
                    case 4:
                        combinedPhraseQTWWeight = Math.max(
                                                           phraseTermWeights[i], phraseTermWeights[i + 1]);
                        break;
                    default:
                        combinedPhraseQTWWeight = 1.0d;
                }
                double s = scoreFDSD(SD, i, ips[i], i+1, ips[i + 1],
                                     avgDocLen);
                finalScore += combinedPhraseQTWWeight * w_o * s;
            }
        } else {
            for (i = 0; i < numPhraseTerms - 1; i++) {
                INNERTERM: for (int j = i + 1; j < numPhraseTerms; j++) {
                    if (!okToUse[i] || !okToUse[j])
                        continue INNERTERM;
                    double combinedPhraseQTWWeight;
                    switch (phraseQTWfnid) {
                        case 1:
                            combinedPhraseQTWWeight = 0.5
                            * phraseTermWeights[i] + 0.5
                            * phraseTermWeights[j];
                            break;
                        case 2:
                            combinedPhraseQTWWeight = phraseTermWeights[i]
                            * phraseTermWeights[j];
                            break;
                        case 3:
                            combinedPhraseQTWWeight = Math.min(
                                                               phraseTermWeights[i], phraseTermWeights[j]);
                            break;
                        case 4:
                            combinedPhraseQTWWeight = Math.max(
                                                               phraseTermWeights[i], phraseTermWeights[j]);
                            break;
                        default:
                            combinedPhraseQTWWeight = 1.0d;
                    }
                    
                    double s = scoreFDSD(SD, i, ips[i], j, ips[j], avgDocLen);
                    finalScore += w_u * combinedPhraseQTWWeight * s;
                }
            }
        }
        return finalScore;
    }

	protected static int countTrue(final boolean[] in) {
		int count = 0;
		for (boolean b : in)
		{
			if (b) count++;
		}
		return count;
	}
	/** 
	 * Constructs an instance of the DependenceScoreModifier.
	 */
	public DependenceScoreModifier() {
		super();
	}
	/** Sets the collection statistics used to score the documents (number of documents in the collection, etc)*/
	public void setCollectionStatistics(CollectionStatistics cs, Index _index) {
		numTokens = (double)cs.getNumberOfTokens();
		long numDocs = (long) (cs.getNumberOfDocuments());
		avgDocLen = ((double) (numTokens - numDocs
				* (ngramLength - 1)))
				/ (double) numDocs;
		
	}
	/** Calculate the score for a document (from the given posting for that document)*/
	public double score(Posting[] postings) {
		double score = 0;
		boolean SD = true;
		double _avgDocLen = 0.0d;
		if (SD)
		{
			for(int i=0;i<postings.length-1;i++)
			{
				score += scoreFDSD(SD, i, postings[i], i+1, postings[i+1], _avgDocLen);
			}
		}
		return w_o * score;
	}

	/**
	 * how likely is it that these two postings have so many near-occurrences,
	 * given the length of this document
	 */
	protected double scoreFDSD(boolean SD, int i, final Posting ip1, int j, final Posting ip2, final double _avgDocLen) {
			
		final int[] blocks1 = ((BlockPosting) ip1).getPositions();
		final int[] blocks2 = ((BlockPosting) ip2).getPositions();
		int docLength = ip1.getDocumentLength();
			
		final int matchingNGrams = SD
			? Distance.noTimesSameOrder(blocks1, blocks2, ngramLength, docLength) 
			: Distance.noTimes(blocks1, blocks2, ngramLength, docLength);
		//System.err.println(this.getClass().getSimpleName() + " matchingNGrams="+matchingNGrams);
		final double s = scoreFDSD(matchingNGrams, docLength);
		if (Double.isNaN(s))
		{
			logger.warn(this.getClass().getSimpleName() + " returned NaN for document " 
				+ ip1.getId() + " "+i+","+j+" pf="+matchingNGrams + " l="+ docLength);
		}	
		return s;
	}

}
