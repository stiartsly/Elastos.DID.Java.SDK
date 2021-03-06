/*
 * Copyright (c) 2019 Elastos Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.elastos.did;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.elastos.did.DIDDocument.PublicKey;
import org.elastos.did.DIDStorage.ReEncryptor;
import org.elastos.did.exception.DIDBackendException;
import org.elastos.did.exception.DIDDeactivatedException;
import org.elastos.did.exception.DIDException;
import org.elastos.did.exception.DIDExpiredException;
import org.elastos.did.exception.DIDNotFoundException;
import org.elastos.did.exception.DIDStoreException;
import org.elastos.did.exception.InvalidKeyException;
import org.elastos.did.exception.MalformedCredentialException;
import org.elastos.did.exception.MalformedDIDException;
import org.elastos.did.exception.MalformedDocumentException;
import org.elastos.did.exception.WrongPasswordException;
import org.elastos.did.meta.CredentialMeta;
import org.elastos.did.meta.DIDMeta;
import org.elastos.did.util.Aes256cbc;
import org.elastos.did.util.Base58;
import org.elastos.did.util.Base64;
import org.elastos.did.util.EcdsaSigner;
import org.elastos.did.util.HDKey;
import org.elastos.did.util.JsonHelper;
import org.elastos.did.util.LRUCache;
import org.spongycastle.crypto.CryptoException;
import org.spongycastle.crypto.digests.SHA256Digest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DIDStore {
	private static final int CACHE_INITIAL_CAPACITY = 16;
	private static final int CACHE_MAX_CAPACITY = 32;

	public static final int DID_HAS_PRIVATEKEY = 0;
	public static final int DID_NO_PRIVATEKEY = 1;
	public static final int DID_ALL	= 2;

	private static final String DID_EXPORT = "did.elastos.export/1.0";

	private Map<DID, DIDDocument> didCache;
	private Map<DIDURL, VerifiableCredential> vcCache;

	private DIDStorage storage;
	private DIDBackend backend;

	public interface ConflictHandle {
		DIDDocument merge(DIDDocument chainCopy, DIDDocument localCopy);
	}

	private DIDStore(int initialCacheCapacity, int maxCacheCapacity,
			DIDAdapter adapter, DIDStorage storage) {
		if (maxCacheCapacity > 0) {
			this.didCache = LRUCache.createInstance(initialCacheCapacity, maxCacheCapacity);
			this.vcCache = LRUCache.createInstance(initialCacheCapacity, maxCacheCapacity);
		}

		this.backend = DIDBackend.getInstance(adapter);
		this.storage = storage;
	}

	public static DIDStore open(String type, String location,
			int initialCacheCapacity, int maxCacheCapacity,
			DIDAdapter adapter) throws DIDStoreException {
		if (type == null || location == null || location.isEmpty() ||
				maxCacheCapacity < initialCacheCapacity || adapter == null)
			throw new IllegalArgumentException();

		if (!type.equals("filesystem"))
			throw new DIDStoreException("Unsupported store type: " + type);

		DIDStorage storage = new FileSystemStorage(location);
		return new DIDStore(initialCacheCapacity, maxCacheCapacity,
				adapter, storage);
	}

	public static DIDStore open(String type, String location, DIDAdapter adapter)
			throws DIDStoreException {
		return open(type, location, CACHE_INITIAL_CAPACITY,
				CACHE_MAX_CAPACITY, adapter);
	}

	public boolean containsPrivateIdentity() throws DIDStoreException {
		return storage.containsPrivateIdentity();
	}

	protected static String encryptToBase64(byte[] input, String passwd)
			throws DIDStoreException {
		byte[] cipher;
		try {
			cipher = Aes256cbc.encrypt(input, passwd);
		} catch (CryptoException e) {
			throw new DIDStoreException("Encrypt data error.", e);
		}

		return Base64.encodeToString(cipher,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
	}

	protected static byte[] decryptFromBase64(String input, String storepass)
			throws DIDStoreException {
		byte[] cipher = Base64.decode(input,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
		try {
			return Aes256cbc.decrypt(cipher, storepass);
		} catch (CryptoException e) {
			throw new WrongPasswordException("Decrypt private key error.", e);
		}
	}

	// Initialize & create new private identity and save it to DIDStore.
	public void initPrivateIdentity(String language, String mnemonic,
			String passphrase, String storepass, boolean force)
			throws DIDStoreException {
		if (mnemonic == null)
			throw new IllegalArgumentException("Invalid mnemonic.");

		if (storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException("Invalid password.");

		try {
			Mnemonic mc = Mnemonic.getInstance(language);
			if (!mc.isValid(mnemonic))
				throw new IllegalArgumentException("Invalid mnemonic.");
		} catch (DIDException e) {
			throw new IllegalArgumentException(e);
		}

		if (containsPrivateIdentity() && !force)
			throw new DIDStoreException("Already has private indentity.");

		if (passphrase == null)
			passphrase = "";

		HDKey privateIdentity = HDKey.fromMnemonic(mnemonic, passphrase);

		initPrivateIdentity(privateIdentity, storepass);

		// Save mnemonic
		String encryptedMnemonic = encryptToBase64(
				mnemonic.getBytes(), storepass);
		storage.storeMnemonic(encryptedMnemonic);

	}

	public void initPrivateIdentity(String language, String mnemonic,
			String passphrase, String storepass) throws DIDStoreException {
		initPrivateIdentity(language, mnemonic, passphrase, storepass, false);
	}

	public void initPrivateIdentity(String extentedPrivateKey, String storepass,
			boolean force) throws DIDStoreException {
		if (extentedPrivateKey == null || extentedPrivateKey.isEmpty())
			throw new IllegalArgumentException("Invalid extended private key.");

		if (storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException("Invalid password.");

		if (containsPrivateIdentity() && !force)
			throw new DIDStoreException("Already has private indentity.");

		HDKey privateIdentity = HDKey.deserialize(Base58.decode(extentedPrivateKey));
		initPrivateIdentity(privateIdentity, storepass);
	}

	public void initPrivateIdentity(String extentedPrivateKey, String storepass)
			throws DIDStoreException {
		initPrivateIdentity(extentedPrivateKey, storepass, false);
	}

	private void initPrivateIdentity(HDKey privateIdentity, String storepass)
			throws DIDStoreException {
		// Save extended root private key
		String encryptedIdentity = encryptToBase64(
				privateIdentity.serialize(), storepass);
		storage.storePrivateIdentity(encryptedIdentity);

		// Save index
		storage.storePrivateIdentityIndex(0);

		privateIdentity.wipe();
	}

	public String exportMnemonic(String storepass) throws DIDStoreException {
		if (storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException("Invalid password.");

		String encryptedMnemonic = storage.loadMnemonic();
		return new String(decryptFromBase64(encryptedMnemonic, storepass));
	}

	// initialized from saved private identity from DIDStore.
	protected HDKey loadPrivateIdentity(String storepass)
			throws DIDStoreException {
		if (!containsPrivateIdentity())
			return null;

		HDKey privateIdentity = null;

		byte[] keyData = decryptFromBase64(storage.loadPrivateIdentity(), storepass);
		if (keyData.length == HDKey.SEED_BYTES) {
			privateIdentity = HDKey.fromSeed(keyData);

			// convert to extended root private key
			String encryptedIdentity = encryptToBase64(
					privateIdentity.serialize(), storepass);
			storage.storePrivateIdentity(encryptedIdentity);
		} else if (keyData.length == HDKey.EXTENDED_PRIVATE_BYTES){
			privateIdentity = HDKey.deserialize(keyData);
		} else {
			throw new DIDStoreException("Invalid private identity.");
		}

		Arrays.fill(keyData, (byte)0);

		return privateIdentity;
	}

	public void synchronize(ConflictHandle handle, String storepass)
			throws DIDBackendException, DIDStoreException {
		if (handle == null || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		int nextIndex = storage.loadPrivateIdentityIndex();
		HDKey privateIdentity = loadPrivateIdentity(storepass);
		if (privateIdentity == null)
			throw new DIDStoreException("DID Store does not contains private identity.");

		try {
			int blanks = 0;
			int i = 0;

			while (i < nextIndex || blanks < 20) {
				HDKey.DerivedKey key = privateIdentity.derive(i++);
				DID did = new DID(DID.METHOD, key.getAddress());

				try {
					DIDDocument chainCopy = null;
					try {
						chainCopy = DIDBackend.resolve(did, true);
					} catch (DIDExpiredException | DIDDeactivatedException e) {
						continue;
					}

					if (chainCopy != null) {
						DIDDocument finalCopy = chainCopy;

						DIDDocument localCopy = loadDid(did);
						if (localCopy != null) {
							if (localCopy.getMeta().getSignature() == null ||
									!localCopy.getProof().getSignature().equals(
									localCopy.getMeta().getSignature())) {
								// Local copy was modified
								finalCopy = handle.merge(chainCopy, localCopy);

								if (finalCopy == null || !finalCopy.getSubject().equals(did))
									throw new DIDStoreException("deal with local modification error.");
							}
						}

						// Save private key
						storePrivateKey(did, finalCopy.getDefaultPublicKey(),
								key.serialize(), storepass);

						storeDid(finalCopy);

						if (i >= nextIndex)
							storage.storePrivateIdentityIndex(i);

						blanks = 0;
					} else {
						if (i >= nextIndex)
							blanks++;
					}
				} finally {
					key.wipe();
				}
			}
		} finally {
			privateIdentity.wipe();
		}
	}

	public void synchronize(String storepass)
			throws DIDBackendException, DIDStoreException {
		synchronize((c, l) -> l, storepass);
	}

	public CompletableFuture<Void> synchronizeAsync(
			ConflictHandle handle, String storepass) {
		if (handle == null || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
			try {
				synchronize(handle, storepass);
			} catch (DIDBackendException | DIDStoreException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<Void> synchronizeAsync(String storepass) {
		return synchronizeAsync((c, l) -> l, storepass);
	}

	public DIDDocument newDid(int index, String alias, String storepass)
			throws DIDStoreException {
		if (index < 0 || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		HDKey privateIdentity = loadPrivateIdentity(storepass);
		if (privateIdentity == null)
			throw new DIDStoreException("DID Store not contains private identity.");

		HDKey.DerivedKey key = privateIdentity.derive(index);
		try {
			DID did = new DID(DID.METHOD, key.getAddress());
			DIDDocument doc = loadDid(did);
			if (doc != null)
				throw new DIDStoreException("DID already exists.");

			DIDURL id = new DIDURL(did, "primary");
			storePrivateKey(did, id, key.serialize(), storepass);

			DIDDocument.Builder db = new DIDDocument.Builder(did, this);
			db.addAuthenticationKey(id, key.getPublicKeyBase58());
			doc = db.seal(storepass);
			doc.getMeta().setAlias(alias);
			storeDid(doc);
			return doc;
		} finally {
			privateIdentity.wipe();
			key.wipe();
		}
	}

	public DIDDocument newDid(int index, String storepass) throws DIDStoreException {
		return newDid(index, null, storepass);
	}

	public DIDDocument newDid(String alias, String storepass)
			throws DIDStoreException {
		int nextIndex = storage.loadPrivateIdentityIndex();
		DIDDocument doc = newDid(nextIndex++, alias, storepass);
		storage.storePrivateIdentityIndex(nextIndex);
		return doc;
	}

	public DIDDocument newDid(String storepass) throws DIDStoreException {
		return newDid(null, storepass);
	}

	public DID getDid(int index, String storepass) throws DIDStoreException {
		if (index < 0 || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		HDKey privateIdentity = loadPrivateIdentity(storepass);
		if (privateIdentity == null)
			throw new DIDStoreException("DID Store not contains private identity.");

		HDKey.DerivedKey key = privateIdentity.derive(index);
		DID did = new DID(DID.METHOD, key.getAddress());
		privateIdentity.wipe();
		key.wipe();
		return did;
	}

	public String publishDid(DID did, int confirms,
			DIDURL signKey, boolean force, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		if (did == null || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		DIDDocument doc = loadDid(did);
		if (doc == null)
			throw new DIDStoreException("Can not find the document for " + did);

		if (doc.isDeactivated())
			throw new DIDStoreException("DID already deactivated.");

		String lastTxid = null;
		DIDDocument resolvedDoc = did.resolve();
		if (resolvedDoc != null) {
			if (resolvedDoc.isDeactivated())
				throw new DIDStoreException("DID already deactivated.");

			if (!force) {
				String localTxid = doc.getMeta().getTransactionId();
				String localSignature = doc.getMeta().getSignature();

				String resolvedTxid = resolvedDoc.getTransactionId();
				String reolvedSignautre = resolvedDoc.getProof().getSignature();

				if (localTxid == null && localSignature == null)
					throw new DIDStoreException("DID document not up-to-date");

				if (localTxid != null && !localTxid.isEmpty() && !localTxid.equals(resolvedTxid))
					throw new DIDStoreException("DID document not up-to-date");

				if (localSignature != null && !localSignature.equals(reolvedSignautre))
					throw new DIDStoreException("DID document not up-to-date");
			}

			lastTxid = resolvedDoc.getTransactionId();
		}

		if (signKey == null)
			signKey = doc.getDefaultPublicKey();

		if (lastTxid == null || lastTxid.isEmpty())
			lastTxid = backend.create(doc, confirms,
					signKey, storepass);
		else
			lastTxid = backend.update(doc, lastTxid, confirms,
					signKey, storepass);

		if (lastTxid != null)
			doc.getMeta().setTransactionId(lastTxid);
		doc.getMeta().setSignature(doc.getProof().getSignature());
		storage.storeDidMeta(doc.getSubject(), doc.getMeta());

		return lastTxid;
	}

	public String publishDid(DID did, int confirms,
			DIDURL signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return publishDid(did, confirms, signKey, false, storepass);
	}

	public String publishDid(String did, int confirms,
			String signKey, boolean force, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		DID _did = null;
		DIDURL _signKey = null;

		try {
			_did = new DID(did);
			_signKey = signKey == null ? null : new DIDURL(_did, signKey);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return publishDid(_did, confirms, _signKey, force, storepass);
	}

	public String publishDid(String did, int confirms,
			String signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return publishDid(did, confirms, signKey, false, storepass);
	}

	public String publishDid(DID did, DIDURL signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return publishDid(did, 0, signKey, storepass);
	}

	public String publishDid(String did, String signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return publishDid(did, 0, signKey, storepass);
	}

	public String publishDid(DID did, int confirms, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return publishDid(did, confirms, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String publishDid(String did, int confirms, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return publishDid(did, confirms, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String publishDid(DID did, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return publishDid(did, 0, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String publishDid(String did, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return publishDid(did, 0, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public CompletableFuture<String> publishDidAsync(DID did, int confirms,
			DIDURL signKey, boolean force, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return publishDid(did, confirms, signKey, force, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> publishDidAsync(String did, int confirms,
			String signKey, boolean force, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return publishDid(did, confirms, signKey, force, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> publishDidAsync(DID did, int confirms,
			DIDURL signKey, String storepass) {
		return publishDidAsync(did, confirms, signKey, false, storepass);
	}

	public CompletableFuture<String> publishDidAsync(String did, int confirms,
			String signKey, String storepass) {
		return publishDidAsync(did, confirms, signKey, false, storepass);
	}

	public CompletableFuture<String> publishDidAsync(DID did,
			DIDURL signKey, String storepass) {
		return publishDidAsync(did, 0, signKey, storepass);
	}

	public CompletableFuture<String> publishDidAsync(String did,
			String signKey, String storepass) {
		return publishDidAsync(did, 0, signKey, storepass);
	}

	public CompletableFuture<String> publishDidAsync(DID did,
			int confirms, String storepass) {
		return publishDidAsync(did, confirms, null, storepass);
	}

	public CompletableFuture<String> publishDidAsync(String did,
			int confirms, String storepass) {
		return publishDidAsync(did, confirms, null, storepass);
	}

	public CompletableFuture<String> publishDidAsync(DID did, String storepass) {
		return publishDidAsync(did, 0, null, storepass);
	}

	public CompletableFuture<String> publishDidAsync(String did, String storepass) {
		return publishDidAsync(did, 0, null, storepass);
	}

	// Deactivate self use authentication keys
	public String deactivateDid(DID did, int confirms,
			DIDURL signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		if (did == null || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		// Document should use the IDChain's copy
		boolean localCopy = false;
		DIDDocument doc = DIDBackend.resolve(did);
		if (doc == null) {
			// Fail-back: try to load document from local store
			doc = loadDid(did);
			if (doc == null)
				throw new DIDNotFoundException(did.toString());
			else
				localCopy = true;
		} else {
			doc.getMeta().setStore(this);
		}

		if (signKey == null) {
			signKey = doc.getDefaultPublicKey();
		} else {
			if (!doc.isAuthenticationKey(signKey))
				throw new InvalidKeyException("Not an authentication key.");
		}

		String txid = backend.deactivate(doc,
				confirms, signKey, storepass);

		// Save deactivated status to DID metadata
		if (localCopy) {
			doc.getMeta().setDeactivated(true);
			storage.storeDidMeta(did, doc.getMeta());
		}

		return txid;
	}

	public String deactivateDid(String did, int confirms,
			String signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		DID _did = null;
		DIDURL _signKey = null;
		try {
			_did = new DID(did);
			_signKey = signKey == null ? null : new DIDURL(_did, signKey);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return deactivateDid(_did, confirms, _signKey, storepass);
	}

	public String deactivateDid(DID did, DIDURL signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(did, 0, signKey, storepass);
	}

	public String deactivateDid(String did, String signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(did, 0, signKey, storepass);
	}

	public String deactivateDid(DID did, int confirms, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return deactivateDid(did, confirms, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String deactivateDid(String did, int confirms, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return deactivateDid(did, confirms, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String deactivateDid(DID did, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return deactivateDid(did, 0, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code.
		}
	}

	public String deactivateDid(String did, String storepass)
			throws DIDBackendException, DIDStoreException {
		try {
			return deactivateDid(did, 0, null, storepass);
		} catch (InvalidKeyException ignore) {
			return null; // Dead code
		}
	}

	public CompletableFuture<String> deactivateDidAsync(DID did,
			int confirms, DIDURL signKey, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return deactivateDid(did, confirms, signKey, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> deactivateDidAsync(String did,
			int confirms, String signKey, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return deactivateDid(did, confirms, signKey, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> deactivateDidAsync(DID did,
			DIDURL signKey, String storepass) {
		return deactivateDidAsync(did, 0, signKey, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(String did,
			String signKey, String storepass) {
		return deactivateDidAsync(did, 0, signKey, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(DID did,
			int confirms, String storepass) {
		return deactivateDidAsync(did, confirms, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(String did,
			int confirms, String storepass) {
		return deactivateDidAsync(did, confirms, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(DID did,
			String storepass) {
		return deactivateDidAsync(did, 0, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(String did,
			String storepass) {
		return deactivateDidAsync(did, 0, null, storepass);
	}

	// Deactivate target DID with authorization
	public String deactivateDid(DID target, DID did, int confirms, DIDURL signKey,
			String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		if (target == null || did == null ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		// All documents should use the IDChain's copy
		DIDDocument doc = DIDBackend.resolve(did);
		if (doc == null) {
			// Fail-back: try to load document from local store
			doc = loadDid(did);
			if (doc == null)
				throw new DIDNotFoundException(did.toString());
		} else {
			doc.getMeta().setStore(this);
		}

		PublicKey signPk = null;
		if (signKey != null) {
			signPk = doc.getAuthenticationKey(signKey);
			if (signPk == null)
				throw new InvalidKeyException("Not an authentication key.");
		}

		DIDDocument targetDoc = DIDBackend.resolve(target);
		if (targetDoc == null)
			throw new DIDNotFoundException(target.toString());

		if (targetDoc.getAuthorizationKeyCount() == 0)
			throw new InvalidKeyException("No matched authorization key.");

		// The authorization key id in the target doc
		DIDURL targetSignKey = null;

		List<PublicKey> authorizationKeys = targetDoc.getAuthorizationKeys();
		matchloop: for (PublicKey targetPk : authorizationKeys) {
			if (!targetPk.getController().equals(did))
				continue;

			if (signPk != null) {
				if (!targetPk.getPublicKeyBase58().equals(signPk.getPublicKeyBase58()))
					continue;

				targetSignKey = targetPk.getId();
				break;
			} else {
				List<PublicKey> pks = doc.getAuthenticationKeys();
				for (PublicKey pk : pks) {
					if (pk.getPublicKeyBase58().equals(targetPk.getPublicKeyBase58())) {
						signPk = pk;
						signKey = signPk.getId();
						targetSignKey = targetPk.getId();
						break matchloop;
					}
				}
			}
		}

		if (targetSignKey == null)
			throw new InvalidKeyException("No matched authorization key.");

		return backend.deactivate(target, targetSignKey,
				doc, confirms, signKey, storepass);
	}

	public String deactivateDid(String target, String did, int confirms,
			String signKey, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		DID _target = null;
		DID _did = null;
		DIDURL _signKey = null;
		try {
			_target = new DID(target);
			_did = new DID(did);
			_signKey = signKey == null ? null : new DIDURL(_did, signKey);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return deactivateDid(_target, _did, confirms, _signKey, storepass);
	}

	public String deactivateDid(DID target, DID did, DIDURL signKey,
			String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(target, did, 0, signKey, storepass);
	}

	public String deactivateDid(String target, String did, String signKey,
			String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(target, did, 0, signKey, storepass);
	}

	public String deactivateDid(DID target, DID did, int confirms,
			String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(target, did, confirms, null, storepass);
	}

	public String deactivateDid(String target, String did, int confirms,
			String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(target, did, confirms, null, storepass);
	}

	public String deactivateDid(DID target, DID did, String storepass)
			throws DIDBackendException, DIDStoreException, InvalidKeyException {
		return deactivateDid(target, did, 0, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(DID target, DID did,
			int confirms, DIDURL signKey, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return deactivateDid(target, did, confirms, signKey, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> deactivateDidAsync(String target,
			String did, int confirms, String signKey, String storepass) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return deactivateDid(target, did, confirms, signKey, storepass);
			} catch (DIDBackendException | DIDStoreException | InvalidKeyException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<String> deactivateDidAsync(DID target, DID did,
			DIDURL signKey, String storepass) {
		return deactivateDidAsync(target, did, 0, signKey, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(String target,
			String did, String signKey, String storepass) {
		return deactivateDidAsync(target, did, 0, signKey, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(DID target, DID did,
			int confirms, String storepass) {
		return deactivateDidAsync(target, did, confirms, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(String target,
			String did, int confirms, String storepass) {
		return deactivateDidAsync(target, did, confirms, null, storepass);
	}

	public CompletableFuture<String> deactivateDidAsync(DID target,
			DID did, String storepass) {
		return deactivateDidAsync(target, did, 0, null, storepass);
	}

	public void storeDid(DIDDocument doc, String alias) throws DIDStoreException {
		doc.getMeta().setAlias(alias);
		storeDid(doc);
	}

	public void storeDid(DIDDocument doc) throws DIDStoreException {
		if (doc == null)
			throw new IllegalArgumentException();

		storage.storeDid(doc);

		DIDMeta meta = loadDidMeta(doc.getSubject());
		meta.merge(doc.getMeta());
		meta.setStore(this);
		doc.setMeta(meta);

		storage.storeDidMeta(doc.getSubject(), meta);

		for (VerifiableCredential vc : doc.getCredentials())
			storeCredential(vc);

		if (didCache != null)
			didCache.put(doc.getSubject(), doc);
	}

	protected void storeDidMeta(DID did, DIDMeta meta) throws DIDStoreException {
		storage.storeDidMeta(did, meta);

		if (didCache != null) {
			DIDDocument doc = didCache.get(did);
			if (doc != null)
				doc.setMeta(meta);
		}
	}

	protected void storeDidMeta(String did, DIDMeta meta) throws DIDStoreException{
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		storeDidMeta(_did, meta);
	}

	protected DIDMeta loadDidMeta(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		DIDMeta meta = null;
		DIDDocument doc = null;

		if (didCache != null) {
			doc = didCache.get(did);
			if (doc != null) {
				meta = doc.getMeta();
				if (meta != null)
					return meta;
			}
		}

		meta = storage.loadDidMeta(did);
		if (doc != null)
			doc.setMeta(meta);

		return meta;
	}

	protected DIDMeta loadDidMeta(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return loadDidMeta(_did);
	}

	public DIDDocument loadDid(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		DIDDocument doc;

		if (didCache != null) {
			doc = didCache.get(did);
			if (doc != null)
				return doc;
		}

		doc = storage.loadDid(did);
		if (doc != null) {
			doc.setMeta(storage.loadDidMeta(did));
			doc.getMeta().setStore(this);
		}

		if (doc != null && didCache != null)
			didCache.put(doc.getSubject(), doc);

		return doc;
	}

	public DIDDocument loadDid(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return loadDid(_did);
	}

	public boolean containsDid(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		return storage.containsDid(did);
	}

	public boolean containsDid(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return containsDid(_did);
	}

	public boolean deleteDid(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		didCache.remove(did);
		return storage.deleteDid(did);
	}

	public boolean deleteDid(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return deleteDid(_did);
	}

	public List<DID> listDids(int filter) throws DIDStoreException {
		List<DID> dids = storage.listDids(filter);

		for (DID did : dids) {
			DIDMeta meta = loadDidMeta(did);
			meta.setStore(this);
			did.setMeta(meta);
		}

		return dids;
	}

	public void storeCredential(VerifiableCredential credential, String alias)
			throws DIDStoreException {
		credential.getMeta().setAlias(alias);
		storeCredential(credential);
	}

	public void storeCredential(VerifiableCredential credential)
			throws DIDStoreException {
		if (credential == null)
			throw new IllegalArgumentException();

		storage.storeCredential(credential);

		CredentialMeta meta = loadCredentialMeta(
				credential.getSubject().getId(), credential.getId());
		meta.merge(credential.getMeta());
		meta.setStore(this);
		credential.setMeta(meta);

		credential.getMeta().setStore(this);
		storage.storeCredentialMeta(credential.getSubject().getId(),
				credential.getId(), meta);

		if (vcCache != null)
			vcCache.put(credential.getId(), credential);
	}

	protected void storeCredentialMeta(DID did, DIDURL id, CredentialMeta meta)
			throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		storage.storeCredentialMeta(did, id, meta);

		if (vcCache != null) {
			VerifiableCredential vc = vcCache.get(id);
			if (vc != null) {
				vc.setMeta(meta);
			}
		}
	}

	protected void storeCredentialMeta(String did, String id, CredentialMeta meta)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		storeCredentialMeta(_did, _id, meta);
	}

	protected CredentialMeta loadCredentialMeta(DID did, DIDURL id)
			throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		CredentialMeta meta = null;
		VerifiableCredential vc = null;

		if (vcCache != null) {
			vc = vcCache.get(id);
			if (vc != null) {
				meta = vc.getMeta();
				if (meta != null)
					return meta;
			}
		}

		meta = storage.loadCredentialMeta(did, id);
		if (vc != null)
			vc.setMeta(meta);

		return meta;
	}

	protected CredentialMeta loadCredentialMeta(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return loadCredentialMeta(_did, _id);
	}

	public VerifiableCredential loadCredential(DID did, DIDURL id)
			throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		VerifiableCredential vc;

		if (vcCache != null) {
			vc = vcCache.get(id);
			if (vc != null)
				return vc;
		}

		vc = storage.loadCredential(did, id);
		if (vc != null && vcCache != null)
			vcCache.put(vc.getId(), vc);

		return vc;
	}

	public VerifiableCredential loadCredential(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return loadCredential(_did, _id);
	}

	public boolean containsCredentials(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		return storage.containsCredentials(did);
	}

	public boolean containsCredentials(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return containsCredentials(_did);
	}

	public boolean containsCredential(DID did, DIDURL id)
			throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		return storage.containsCredential(did, id);
	}

	public boolean containsCredential(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return containsCredential(_did, _id);
	}

	public boolean deleteCredential(DID did, DIDURL id) throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		vcCache.remove(id);
		return storage.deleteCredential(did, id);
	}

	public boolean deleteCredential(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return deleteCredential(_did, _id);
	}

	public List<DIDURL> listCredentials(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		List<DIDURL> ids = storage.listCredentials(did);

		for (DIDURL id : ids) {
			CredentialMeta meta = loadCredentialMeta(did, id);
			meta.setStore(this);
			id.setMeta(meta);
		}

		return ids;
	}

	public List<DIDURL> listCredentials(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return listCredentials(_did);
	}

	public List<DIDURL> selectCredentials(DID did, DIDURL id, String[] type)
			throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		if ((id == null) && (type == null || type.length == 0))
			throw new IllegalArgumentException();

		return storage.selectCredentials(did, id, type);
	}

	public List<DIDURL> selectCredentials(String did, String id, String[] type)
			throws DIDStoreException {
		if (did == null || did.isEmpty())
			throw new IllegalArgumentException();

		if ((id == null || id.isEmpty()) && type == null || type.length == 0)
			throw new IllegalArgumentException();

		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		DIDURL _id = id == null ? null : new DIDURL(_did, id);
		return selectCredentials(_did, _id, type);
	}

	public void storePrivateKey(DID did, DIDURL id, byte[] privateKey,
			String storepass) throws DIDStoreException {
		if (did == null || id == null ||
				privateKey == null || privateKey.length == 0 ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		String encryptedKey = encryptToBase64(privateKey, storepass);
		storage.storePrivateKey(did, id, encryptedKey);
	}

	public void storePrivateKey(String did, String id, byte[] privateKey,
			String storepass) throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		storePrivateKey(_did, _id, privateKey, storepass);
	}

	protected String loadPrivateKey(DID did, DIDURL id)
			throws DIDStoreException {
		return storage.loadPrivateKey(did, id);
	}

	public boolean containsPrivateKeys(DID did) throws DIDStoreException {
		if (did == null)
			throw new IllegalArgumentException();

		return storage.containsPrivateKeys(did);
	}

	public boolean containsPrivateKeys(String did) throws DIDStoreException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return containsPrivateKeys(_did);
	}

	public boolean containsPrivateKey(DID did, DIDURL id)
			throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		return storage.containsPrivateKey(did, id);
	}

	public boolean containsPrivateKey(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return containsPrivateKey(_did, _id);
	}

	public boolean deletePrivateKey(DID did, DIDURL id) throws DIDStoreException {
		if (did == null || id == null)
			throw new IllegalArgumentException();

		return storage.deletePrivateKey(did, id);
	}

	public boolean deletePrivateKey(String did, String id)
			throws DIDStoreException {
		DID _did = null;
		DIDURL _id = null;
		try {
			_did = new DID(did);
			_id = new DIDURL(_did, id);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		return deletePrivateKey(_did, _id);
	}

	public String sign(DID did, DIDURL id, String storepass, byte[] ... data)
			throws DIDStoreException {
		if (did == null || storepass == null || storepass.isEmpty() || data == null)
			throw new IllegalArgumentException();

		if (id == null) {
			DIDDocument doc = loadDid(did);
			if (doc == null)
				throw new DIDStoreException("Can not resolve DID document.");

			id = doc.getDefaultPublicKey();
		}

		byte[] binKey = decryptFromBase64(loadPrivateKey(did, id), storepass);
		HDKey.DerivedKey key = HDKey.DerivedKey.deserialize(binKey);

		byte[] sig = EcdsaSigner.sign(key.getPrivateKeyBytes(), data);

		key.wipe();
		Arrays.fill(binKey, (byte)0);

		return Base64.encodeToString(sig,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
	}

	public String sign(DID did, String storepass, byte[] ... data)
			throws DIDStoreException {
		return sign(did, null, storepass, data);
	}

	public void changePassword(String oldPassword, String newPassword)
			throws DIDStoreException {
		ReEncryptor ree = new ReEncryptor() {
			@Override
			public String reEncrypt(String data) throws DIDStoreException {
				byte[] secret = DIDStore.decryptFromBase64(data, oldPassword);
				String result = DIDStore.encryptToBase64(secret, newPassword);
				Arrays.fill(secret, (byte)0);

				return result;
			}
		};

		storage.changePassword(ree);
	}

	private void exportDid(DID did, JsonGenerator generator, String password,
			String storepass) throws DIDStoreException, IOException {
		// All objects should load directly from storage,
		// avoid affects the cached objects.

		DIDDocument doc = storage.loadDid(did);
		if (doc == null)
			throw new DIDStoreException("Export DID " + did + " failed, not exist.");

		SHA256Digest sha256 = new SHA256Digest();
		byte[] bytes = password.getBytes();
		sha256.update(bytes, 0, bytes.length);

		generator.writeStartObject();

		// Type
		generator.writeStringField("type", DID_EXPORT);
		bytes = DID_EXPORT.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// DID
		String value = did.toString();
		generator.writeStringField("id", value);
		bytes = value.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Create
		Date now = Calendar.getInstance(Constants.UTC).getTime();
		value = JsonHelper.formatDate(now);
		generator.writeStringField("created", value);
		bytes = value.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Document
		generator.writeFieldName("document");
		doc.toJson(generator, false);
		value = doc.toString(true);
		bytes = value.getBytes();
		sha256.update(bytes, 0, bytes.length);

		DIDMeta didMeta = storage.loadDidMeta(did);
		if (didMeta.isEmpty())
			didMeta = null;

		// Credential
		LinkedHashMap<DIDURL, CredentialMeta> vcMetas = null;
		if (storage.containsCredentials(did)) {
			vcMetas = new LinkedHashMap<DIDURL, CredentialMeta>();

			generator.writeFieldName("credential");
			generator.writeStartArray();

			List<DIDURL> ids = listCredentials(did);
			Collections.sort(ids);
			for (DIDURL id : ids) {
				VerifiableCredential vc = storage.loadCredential(did, id);

				vc.toJson(generator, false);
				value = vc.toString(true);
				bytes = value.getBytes();
				sha256.update(bytes, 0, bytes.length);

				CredentialMeta meta = storage.loadCredentialMeta(did, id);
				if (!meta.isEmpty())
					vcMetas.put(id, meta);
			}

			generator.writeEndArray();
		}

		// Private key
		if (storage.containsPrivateKeys(did)) {
			generator.writeFieldName("privatekey");
			generator.writeStartArray();

			List<PublicKey> pks = doc.getPublicKeys();
			for (PublicKey pk : pks) {
				DIDURL id = pk.getId();

				if (storage.containsPrivateKey(did, id)) {
					String csk = storage.loadPrivateKey(did, id);
					byte[] sk = decryptFromBase64(csk, storepass);
					csk = encryptToBase64(sk, password);
					Arrays.fill(sk, (byte)0);

					generator.writeStartObject();

					value = id.toString();
					generator.writeStringField("id", value);
					bytes = value.getBytes();
					sha256.update(bytes, 0, bytes.length);

					generator.writeStringField("key", csk);
					bytes = csk.getBytes();
					sha256.update(bytes, 0, bytes.length);

					generator.writeEndObject();
				}
			}

			generator.writeEndArray();
		}

		// Metadata
		if (didMeta != null || (vcMetas != null && !vcMetas.isEmpty())) {
			generator.writeFieldName("metadata");
			generator.writeStartObject();

			if (didMeta != null) {
				generator.writeFieldName("document");
				value = didMeta.toString();
				generator.writeRawValue(value);
				bytes = value.getBytes();
				sha256.update(bytes, 0, bytes.length);
			}

			if (vcMetas != null && !vcMetas.isEmpty()) {
				generator.writeFieldName("credential");
				generator.writeStartArray();

				for (Map.Entry<DIDURL, CredentialMeta> meta : vcMetas.entrySet()) {
					generator.writeStartObject();

					value = meta.getKey().toString();
					generator.writeStringField("id", value);
					bytes = value.getBytes();
					sha256.update(bytes, 0, bytes.length);

					value = meta.getValue().toString();
					generator.writeFieldName("metadata");
					generator.writeRawValue(value);
					bytes = value.getBytes();
					sha256.update(bytes, 0, bytes.length);

					generator.writeEndObject();
				}
				generator.writeEndArray();
			}
			generator.writeEndObject();
		}

		// Fingerprint
		byte digest[] = new byte[32];
		sha256.doFinal(digest, 0);
		String fingerprint = Base64.encodeToString(digest,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
		generator.writeStringField("fingerprint", fingerprint);

		generator.writeEndObject();
	}

	public void exportDid(DID did, OutputStream out, String password,
			String storepass) throws DIDStoreException, IOException {
		if (did == null || out == null || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		JsonFactory factory = new JsonFactory();
		JsonGenerator generator = factory.createGenerator(out);
		generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		exportDid(did, generator, password, storepass);
		generator.close();
	}

	public void exportDid(String did, OutputStream out, String password,
			String storepass) throws DIDStoreException, IOException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		exportDid(_did, out, password, storepass);
	}

	public void exportDid(DID did, Writer out, String password,
			String storepass) throws DIDStoreException, IOException {
		if (did == null || out == null || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		JsonFactory factory = new JsonFactory();
		JsonGenerator generator = factory.createGenerator(out);
		generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		exportDid(did, generator, password, storepass);
		generator.close();
	}

	public void exportDid(String did, Writer out, String password,
			String storepass) throws DIDStoreException, IOException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		exportDid(_did, out, password, storepass);
	}

	public void exportDid(DID did, File file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (did == null || file == null || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		exportDid(did, new FileWriter(file), password, storepass);
	}

	public void exportDid(String did, File file, String password,
			String storepass) throws DIDStoreException, IOException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		exportDid(_did, file, password, storepass);
	}

	public void exportDid(DID did, String file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (did == null || file == null || file.isEmpty() || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		exportDid(did, new File(file), password, storepass);
	}

	public void exportDid(String did, String file, String password,
			String storepass) throws DIDStoreException, IOException {
		DID _did = null;
		try {
			_did = new DID(did);
		} catch (MalformedDIDException e) {
			throw new IllegalArgumentException(e);
		}

		exportDid(_did, file, password, storepass);
	}

	private void importDid(JsonNode root, String password, String storepass)
			throws DIDStoreException, IOException {
		Class<DIDStoreException> exceptionClass = DIDStoreException.class;

		SHA256Digest sha256 = new SHA256Digest();
		byte[] bytes = password.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Type
		String type = JsonHelper.getString(root, "type", false, null,
				"export type", exceptionClass);
		if (!type.equals(DID_EXPORT))
			throw new DIDStoreException("Invalid export data, unknown type.");
		bytes = type.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// DID
		DID did = JsonHelper.getDid(root, "id", false, null,
				"DID subject", exceptionClass);
		bytes = did.toString().getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Created
		Date created = JsonHelper.getDate(root, "created", true, null,
				"export date", exceptionClass);
		bytes = JsonHelper.formatDate(created).getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Document
		JsonNode node = root.get("document");
		if (node == null)
			throw new DIDStoreException("Missing DID document in the export data");

		DIDDocument doc = null;
		try {
			doc = DIDDocument.fromJson(node);
		} catch (MalformedDocumentException e) {
			throw new DIDStoreException("Invalid export data.", e);
		}

		if (!doc.getSubject().equals(did) || !doc.isGenuine())
			throw new DIDStoreException("Invalid DID document in the export data.");

		bytes = doc.toString(true).getBytes();
		sha256.update(bytes, 0, bytes.length);


		// Credential
		HashMap<DIDURL, VerifiableCredential> vcs = null;
		node = root.get("credential");
		if (node != null) {
			if (!node.isArray())
				throw new DIDStoreException("Invalid export data, wrong credential data.");

			vcs = new HashMap<DIDURL, VerifiableCredential>(node.size());

			for (int i = 0; i < node.size(); i++) {
				VerifiableCredential vc = null;

				try {
					vc = VerifiableCredential.fromJson(node.get(i), did);
				} catch (MalformedCredentialException e) {
					throw new DIDStoreException("Invalid export data.", e);
				}

				if (!vc.getSubject().getId().equals(did) /* || !vc.isGenuine() */)
					throw new DIDStoreException("Invalid credential in the export data.");

				bytes = vc.toString(true).getBytes();
				sha256.update(bytes, 0, bytes.length);

				vcs.put(vc.getId(), vc);
			}
		}

		// Private key
		HashMap<DIDURL, String> sks = null;
		node = root.get("privatekey");
		if (node != null) {
			if (!node.isArray())
				throw new DIDStoreException("Invalid export data, wrong privatekey data.");

			sks = new HashMap<DIDURL, String>(node.size());

			for (int i = 0; i < node.size(); i++) {
				DIDURL id = JsonHelper.getDidUrl(node.get(i), "id", did,
						"privatekey id", exceptionClass);
				String csk = JsonHelper.getString(node.get(i), "key", false, null,
						"privatekey", exceptionClass);

				bytes = id.toString().getBytes();
				sha256.update(bytes, 0, bytes.length);

				bytes = csk.getBytes();
				sha256.update(bytes, 0, bytes.length);

				byte[] sk = decryptFromBase64(csk, password);
				csk = encryptToBase64(sk, storepass);
				Arrays.fill(sk, (byte)0);

				sks.put(id, csk);
			}
		}

		// Metadata
		node = root.get("metadata");
		if (node != null) {
			JsonNode metaNode = node.get("document");
			if (metaNode != null) {
				DIDMeta meta = DIDMeta.fromJson(metaNode, DIDMeta.class);
				doc.setMeta(meta);

				bytes = meta.toString().getBytes();
				sha256.update(bytes, 0, bytes.length);
			}

			metaNode = node.get("credential");
			if (metaNode != null) {
				if (!metaNode.isArray())
					throw new DIDStoreException("Invalid export data, wrong metadata.");

				for (int i = 0; i < metaNode.size(); i++) {
					JsonNode n = metaNode.get(i);

					DIDURL id = JsonHelper.getDidUrl(n, "id", false, null,
							"credential id", exceptionClass);

					bytes = id.toString().getBytes();
					sha256.update(bytes, 0, bytes.length);

					CredentialMeta meta = CredentialMeta.fromJson(
							n.get("metadata"), CredentialMeta.class);

					bytes = meta.toString().getBytes();
					sha256.update(bytes, 0, bytes.length);

					VerifiableCredential vc = vcs.get(id);
					if (vc != null)
						vc.setMeta(meta);
				}
			}
		}

		// Fingerprint
		node = root.get("fingerprint");
		if (node == null)
			throw new DIDStoreException("Missing fingerprint in the export data");
		String refFingerprint = node.asText();

		byte digest[] = new byte[32];
		sha256.doFinal(digest, 0);
		String fingerprint = Base64.encodeToString(digest,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

		if (!fingerprint.equals(refFingerprint))
			throw new DIDStoreException("Invalid export data, the fingerprint mismatch.");

		// Save
		//
		// All objects should load directly from storage,
		// avoid affects the cached objects.
		storage.storeDid(doc);
		storage.storeDidMeta(doc.getSubject(), doc.getMeta());

		for (VerifiableCredential vc : vcs.values()) {
			storage.storeCredential(vc);
			storage.storeCredentialMeta(did, vc.getId(), vc.getMeta());
		}

		for (Map.Entry<DIDURL, String> sk : sks.entrySet()) {
			storage.storePrivateKey(did, sk.getKey(), sk.getValue());
		}
	}

	public void importDid(InputStream in, String password, String storepass)
			throws DIDStoreException, IOException {
		if (in == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(in);
		importDid(root, password, storepass);
	}

	public void importDid(Reader in, String password, String storepass)
			throws DIDStoreException, IOException {
		if (in == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(in);
		importDid(root, password, storepass);
	}

	public void importDid(File file, String password, String storepass)
			throws DIDStoreException, IOException {
		if (file == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(file);
		importDid(root, password, storepass);
	}

	public void importDid(String file, String password, String storepass)
			throws DIDStoreException, IOException {
		if (file == null || file.isEmpty() || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		importDid(new File(file), password, storepass);
	}

	private void exportPrivateIdentity(JsonGenerator generator, String password,
			String storepass) throws DIDStoreException, IOException {
		String encryptedMnemonic = storage.loadMnemonic();
		byte[] plain = decryptFromBase64(encryptedMnemonic, storepass);
		encryptedMnemonic = encryptToBase64(plain, password);
		Arrays.fill(plain, (byte)0);

		String encryptedSeed = storage.loadPrivateIdentity();
		plain = decryptFromBase64(encryptedSeed, storepass);
		encryptedSeed = encryptToBase64(plain, password);
		Arrays.fill(plain, (byte)0);

		int index = storage.loadPrivateIdentityIndex();

		SHA256Digest sha256 = new SHA256Digest();
		byte[] bytes = password.getBytes();
		sha256.update(bytes, 0, bytes.length);

		generator.writeStartObject();

		// Type
		generator.writeStringField("type", DID_EXPORT);
		bytes = DID_EXPORT.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Mnemonic
		generator.writeStringField("mnemonic", encryptedMnemonic);
		bytes = encryptedMnemonic.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Key
		generator.writeStringField("key", encryptedSeed);
		bytes = encryptedSeed.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Index
		generator.writeNumberField("index", index);
		bytes = Integer.toString(index).getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Fingerprint
		byte digest[] = new byte[32];
		sha256.doFinal(digest, 0);
		String fingerprint = Base64.encodeToString(digest,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
		generator.writeStringField("fingerprint", fingerprint);

		generator.writeEndObject();
	}

	public void exportPrivateIdentity(OutputStream out, String password,
			String storepass) throws DIDStoreException, IOException {
		if (out == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		JsonFactory factory = new JsonFactory();
		JsonGenerator generator = factory.createGenerator(out);
		generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		exportPrivateIdentity(generator, password, storepass);
		generator.close();
	}

	public void exportPrivateIdentity(Writer out, String password,
			String storepass) throws DIDStoreException, IOException {
		if (out == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		JsonFactory factory = new JsonFactory();
		JsonGenerator generator = factory.createGenerator(out);
		generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		exportPrivateIdentity(generator, password, storepass);
		generator.close();
	}

	public void exportPrivateIdentity(File file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (file == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		exportPrivateIdentity(new FileWriter(file), password, storepass);
	}

	public void exportPrivateIdentity(String file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (file == null || file.isEmpty() || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		exportPrivateIdentity(new File(file), password, storepass);
	}

	private void importPrivateIdentity(JsonNode root, String password,
			String storepass) throws DIDStoreException, IOException {
		Class<DIDStoreException> exceptionClass = DIDStoreException.class;

		SHA256Digest sha256 = new SHA256Digest();
		byte[] bytes = password.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Type
		String type = JsonHelper.getString(root, "type", false, null,
				"export type", exceptionClass);
		if (!type.equals(DID_EXPORT))
			throw new DIDStoreException("Invalid export data, unknown type.");
		bytes = type.getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Mnemonic
		String encryptedMnemonic = JsonHelper.getString(root, "mnemonic",
				false, null, "mnemonic", exceptionClass);
		bytes = encryptedMnemonic.getBytes();
		sha256.update(bytes, 0, bytes.length);

		byte[] plain = decryptFromBase64(encryptedMnemonic, password);
		encryptedMnemonic = encryptToBase64(plain, storepass);
		Arrays.fill(plain, (byte)0);

		String encryptedSeed = JsonHelper.getString(root, "key",
				false, null, "key", exceptionClass);
		bytes = encryptedSeed.getBytes();
		sha256.update(bytes, 0, bytes.length);

		plain = decryptFromBase64(encryptedSeed, password);
		encryptedSeed = encryptToBase64(plain, storepass);
		Arrays.fill(plain, (byte)0);

		JsonNode node = root.get("index");
		if (node == null || !node.isNumber())
			throw new DIDStoreException("Invalid export data, unknow index.");
		int index = node.asInt();
		bytes = Integer.toString(index).getBytes();
		sha256.update(bytes, 0, bytes.length);

		// Fingerprint
		node = root.get("fingerprint");
		if (node == null)
			throw new DIDStoreException("Missing fingerprint in the export data");
		String refFingerprint = node.asText();

		byte digest[] = new byte[32];
		sha256.doFinal(digest, 0);
		String fingerprint = Base64.encodeToString(digest,
				Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

		if (!fingerprint.equals(refFingerprint))
			throw new DIDStoreException("Invalid export data, the fingerprint mismatch.");

		// Save
		storage.storeMnemonic(encryptedMnemonic);
		storage.storePrivateIdentity(encryptedSeed);
		storage.storePrivateIdentityIndex(index);
	}

	public void importPrivateIdentity(InputStream in, String password,
			String storepass) throws DIDStoreException, IOException {
		if (in == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(in);
		importPrivateIdentity(root, password, storepass);
	}

	public void importPrivateIdentity(Reader in, String password,
			String storepass) throws DIDStoreException, IOException {
		if (in == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(in);
		importPrivateIdentity(root, password, storepass);
	}

	public void importPrivateIdentity(File file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (file == null || password == null || password.isEmpty() ||
				storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
		JsonNode root = mapper.readTree(file);
		importPrivateIdentity(root, password, storepass);
	}

	public void importPrivateIdentity(String file, String password,
			String storepass) throws DIDStoreException, IOException {
		if (file == null || file.isEmpty() || password == null ||
				password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();
		importPrivateIdentity(new File(file), password, storepass);
	}

	public void exportStore(ZipOutputStream out, String password,
			String storepass) throws DIDStoreException, IOException {
		if (out == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ZipEntry ze;

		if (containsPrivateIdentity()) {
			ze = new ZipEntry("privateIdentity");
			out.putNextEntry(ze);
			exportPrivateIdentity(out, password, storepass);
			out.closeEntry();
		}

		List<DID> dids = listDids(DID_ALL);
		for (DID did : dids) {
			ze = new ZipEntry(did.getMethodSpecificId());
			out.putNextEntry(ze);
			exportDid(did, out, password, storepass);
			out.closeEntry();
		}
	}

	public void exportStore(File zipFile, String password, String storepass)
			throws DIDStoreException, IOException {
		if (zipFile == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile));
		exportStore(out, password, storepass);
		out.close();
	}

	public void exportStore(String zipFile, String password, String storepass)
			throws DIDStoreException, IOException {
		if (zipFile == null || zipFile.isEmpty() || password == null
				|| password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		exportStore(new File(zipFile), password, storepass);
	}

	public void importStore(ZipInputStream in, String password, String storepass)
			throws DIDStoreException, IOException {
		if (in == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ZipEntry ze;
		while ((ze = in.getNextEntry()) != null) {
			if (ze.getName().equals("privateIdentity"))
				importPrivateIdentity(in, password, storepass);
			else
				importDid(in, password, storepass);
			in.closeEntry();
		}
	}

	public void importStore(File zipFile, String password, String storepass)
			throws DIDStoreException, IOException {
		if (zipFile == null || password == null || password.isEmpty()
				|| storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile));
		importStore(in, password, storepass);
		in.close();
	}

	public void importStore(String zipFile, String password, String storepass)
			throws DIDStoreException, IOException {
		if (zipFile == null || zipFile.isEmpty() || password == null
				|| password.isEmpty() || storepass == null || storepass.isEmpty())
			throw new IllegalArgumentException();

		importStore(new File(zipFile), password, storepass);
	}
}
