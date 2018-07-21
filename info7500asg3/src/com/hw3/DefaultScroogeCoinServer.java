package com.hw3;

import java.security.*;
import java.util.*;

//Scrooge creates coins by adding outputs to a transaction to his public key.
//In ScroogeCoin, Scrooge can create as many coins as he wants.
//No one else can create a coin.
//A user owns a coin if a coin is transfer to him from its current owner
public class DefaultScroogeCoinServer implements ScroogeCoinServer {

	private KeyPair scroogeKeyPair;
	private ArrayList<Transaction> ledger = new ArrayList();

	//Set scrooge's key pair
	@Override
	public synchronized void init(KeyPair scrooge) {
	//	throw new RuntimeException();
		this.scroogeKeyPair = scrooge;

	}


	//For every 10 minute epoch, this method is called with an unordered list of proposed transactions
	// 		submitted during this epoch.
	//This method goes through the list, checking each transaction for correctness, and accepts as
	// 		many transactions as it can in a "best-effort" manner, but it does not necessarily return
	// 		the maximum number possible.
	//If the method does not accept an valid transaction, the user must try to submit the transaction
	// 		again during the next epoch.
	//Returns a list of hash pointers to transactions accepted for this epoch

	public synchronized List<HashPointer> epochHandler(List<Transaction> txs)  {
		List<HashPointer> list = new ArrayList<HashPointer>();
		while(!txs.isEmpty()){
			List<Transaction> temp = new ArrayList<Transaction>();
			for(Transaction tx:txs){
				if(!isValid(tx)){
					temp.add(tx);
				} else {	
					ledger.add(tx);
					HashPointer hp = new HashPointer(tx.getHash(),ledger.size()-1);
					list.add(hp);
				}
			}
			if(txs.size()==temp.size()) break;
			txs = temp;
		}
		return list;   
	}

	//Returns true if and only if transaction tx meets the following conditions:
	//CreateCoin transaction
	//	(1) no inputs
	//	(2) all outputs are given to Scrooge's public key
	//	(3) all of tx’s output values are positive
	//	(4) Scrooge's signature of the transaction is included

	//PayCoin transaction
	//	(1) all inputs claimed by tx are in the current unspent (i.e. in getUTOXs()),
	//	(2) the signatures on each input of tx are valid,
	//	(3) no UTXO is claimed multiple times by tx,
	//	(4) all of tx’s output values are positive, and
	//	(5) the sum of tx’s input values is equal to the sum of its output values;
	@Override
	public synchronized boolean isValid(Transaction tx) {
		switch (tx.getType()){
		case Create:
            //	(1) no inputs
			if(tx.numInputs()!=0)
				return false; 
			for(Transaction.Output op: tx.getOutputs()){
				//	(2) all outputs are given to Scrooge's public key
				if(op.getPublicKey()!=scroogeKeyPair.getPublic())
					return false;
			    //	(3) all of tx’s output values are positive
				if(op.getValue()<=0)
					return false;
			}
			
            //	(4) Scrooge's signature of the transaction is included
			try {
				Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
				signature.initVerify(scroogeKeyPair.getPublic());
				signature.update(tx.getRawBytes());
				
				if(!signature.verify(tx.getSignature()))
					return false;

			} catch (Exception e) {
				// TODO Auto-generated catch block
				throw new RuntimeException(e);
			}
			return true;
			
		case Pay:
			Set<UTXO> utxo = getUTXOs();
            double inSum = 0;
            for (int i = 0; i < tx.numInputs(); i++) {
                Transaction.Input in = tx.getInputs().get(i);
                int indexOut = in.getIndexOfTxOutput();
                int indexLedger = getLedgerIndex(in.getHashOfOutputTx(), utxo, indexOut, in);
                if(indexLedger < 0 ) {
                    return false;
                }
                
                //(2) the signatures on each input of tx are valid
                Transaction.Output out = ledger.get(indexLedger).getOutput(indexOut);
                inSum = inSum + out.getValue();
                PublicKey pk = out.getPublicKey();
                try {
                    Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
                    sig.initVerify(pk);
                    sig.update(tx.getRawDataToSign(i));
                    if(sig.verify(in.getSignature()) == false) {
                        return false;
                    }             
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            
            double outSum = 0;
            for (Transaction.Output out : tx.getOutputs()) {
                if(!(out.getValue() > 0)) {
                    return false;
                }
                outSum = outSum + out.getValue();
            }
            //(5) the sum of tx’s input values is equal to the sum of its output values;
            if(Math.abs(inSum-outSum)<.000001){ 
            	return true;
            } else {
            	return false;
            }
		
		}
		return false;
	}
	
	private int getLedgerIndex(byte[] hashOfOutputTx, Set<UTXO> utxo, int opindex, Transaction.Input ip) {
		for(int i=0;i<ledger.size();i++){
			if(Arrays.equals(ledger.get(i).getHash(),hashOfOutputTx)){//!
				HashPointer iphp = new HashPointer(ip.getHashOfOutputTx(), i);
				UTXO iputxo = new UTXO(iphp,opindex);
				if(utxo.contains(iputxo)) {
					return i;
				}
			}
		}
		return -1;
	}

	//Returns the complete set of currently unspent transaction outputs on the ledger
	@Override
	public synchronized Set<UTXO> getUTXOs() {
		Set<UTXO> UTXO = new HashSet<UTXO>();
		int ledgerIndex;
		for( ledgerIndex = 0; ledgerIndex<ledger.size();ledgerIndex++){
			Transaction tx = ledger.get(ledgerIndex);
			switch(tx.getType()){
			case Create:
				for ( Transaction.Output create: tx.getOutputs()){
					HashPointer createhp = new HashPointer(tx.getHash(),ledgerIndex);
					UTXO createUTXO = new UTXO(createhp,tx.getIndex(create));
					UTXO.add(createUTXO);
				}
				break;
				
			case Pay:
				// Input 
                for(int i = 0; i < tx.numInputs(); i++) {
                    Transaction.Input in = tx.getInputs().get(i);
                    int indexOut = in.getIndexOfTxOutput();
                    HashPointer hpIn = new HashPointer(in.getHashOfOutputTx(),getLedgerIndex(in.getHashOfOutputTx(), UTXO,indexOut,in));
                    UTXO utxoIn = new UTXO(hpIn, indexOut);
                    UTXO.remove(utxoIn);
               
                }
                // Output
				for(Transaction.Output output: tx.getOutputs()){
					int index = tx.getIndex(output);
					HashPointer outhp = new HashPointer(tx.getHash(),ledgerIndex);
					UTXO outUTXO = new UTXO(outhp,index);
					UTXO.add(outUTXO);
				}
				break;
			}
		}
		return UTXO;
	}

}
