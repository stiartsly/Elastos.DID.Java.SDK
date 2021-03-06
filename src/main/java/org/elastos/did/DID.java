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

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.elastos.did.exception.DIDBackendException;
import org.elastos.did.exception.DIDResolveException;
import org.elastos.did.exception.DIDStoreException;
import org.elastos.did.exception.MalformedDIDException;
import org.elastos.did.meta.DIDMeta;
import org.elastos.did.parser.DIDURLBaseListener;
import org.elastos.did.parser.DIDURLParser;
import org.elastos.did.parser.ParserHelper;

public class DID implements Comparable<DID> {
	public final static String METHOD = "elastos";

	private String method;
	private String methodSpecificId;

	private DIDMeta meta;

	protected DID() {
	}

	protected DID(String method, String methodSpecificId) {
		this.method = method;
		this.methodSpecificId = methodSpecificId;
	}

	public DID(String did) throws MalformedDIDException {
		if (did == null || did.isEmpty())
			throw new IllegalArgumentException();

		try {
			ParserHelper.parse(did, true, new Listener());
		} catch(IllegalArgumentException e) {
			throw new MalformedDIDException(e.getMessage());
		}
	}

	public String getMethod() {
		return method;
	}

	protected void setMethod(String method) {
		this.method = method;
	}

	public String getMethodSpecificId() {
		return methodSpecificId;
	}

	protected void setMethodSpecificId(String methodSpecificId) {
		this.methodSpecificId = methodSpecificId;
	}

	protected void setMeta(DIDMeta meta) {
		this.meta = meta;
	}

	protected DIDMeta getMeta() {
		if (meta == null)
			meta = new DIDMeta();

		return meta;
	}

	public void setExtra(String name, String value) throws DIDStoreException {
		if (name == null || name.isEmpty())
			throw new IllegalArgumentException();

		getMeta().setExtra(name, value);

		if (getMeta().attachedStore())
			getMeta().getStore().storeDidMeta(this, meta);
	}

	public String getExtra(String name) {
		if (name == null || name.isEmpty())
			throw new IllegalArgumentException();

		return getMeta().getExtra(name);
	}

	public void setAlias(String alias) throws DIDStoreException {
		getMeta().setAlias(alias);

		if (getMeta().attachedStore())
			getMeta().getStore().storeDidMeta(this, meta);
	}

	public String getAlias() {
		return getMeta().getAlias();
	}

	public String getTransactionId() {
		return getMeta().getTransactionId();
	}

	public Date getUpdated() {
		return getMeta().getUpdated();
	}

	public boolean isDeactivated() {
		return getMeta().isDeactivated();
	}

	public DIDDocument resolve(boolean force)
			throws DIDBackendException, DIDResolveException {
		DIDDocument doc = DIDBackend.resolve(this, force);
		if (doc != null)
			setMeta(doc.getMeta());

		return doc;
	}

	public DIDDocument resolve()
			throws DIDBackendException, DIDResolveException {
		return resolve(false);
	}


	protected CompletableFuture<DIDDocument> resolveAsync(boolean force) {
		CompletableFuture<DIDDocument> future = CompletableFuture.supplyAsync(() -> {
			try {
				return resolve(force);
			} catch (DIDBackendException e) {
				throw new CompletionException(e);
			}
		});

		return future;
	}

	public CompletableFuture<DIDDocument> resolveAsync(DID did) {
		return resolveAsync(false);
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder(64);
		builder.append("did:")
			.append(method)
			.append(":")
			.append(methodSpecificId);

		return builder.toString();
	}

	@Override
	public int hashCode() {
		return METHOD.hashCode() + methodSpecificId.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;

		if (obj instanceof DID) {
			DID did = (DID)obj;
			boolean eq = method.equals(did.method);
			return eq ? methodSpecificId.equals(did.methodSpecificId) : eq;
		}

		if (obj instanceof String) {
			String did = (String)obj;
			return toString().equals(did);
		}

		return false;
	}

	@Override
	public int compareTo(DID did) {
		if (did == null)
			throw new IllegalArgumentException();

		int rc = method.compareTo(did.method);
		return rc == 0 ? methodSpecificId.compareTo(did.methodSpecificId) : rc;
	}

	class Listener extends DIDURLBaseListener {
		@Override
		public void exitMethod(DIDURLParser.MethodContext ctx) {
			String method = ctx.getText();
			if (!method.equals(DID.METHOD))
				throw new IllegalArgumentException("Unknown method: " + method);

			setMethod(DID.METHOD);
		}

		@Override
		public void exitMethodSpecificString(
				DIDURLParser.MethodSpecificStringContext ctx) {
			setMethodSpecificId(ctx.getText());
		}
	}
}
