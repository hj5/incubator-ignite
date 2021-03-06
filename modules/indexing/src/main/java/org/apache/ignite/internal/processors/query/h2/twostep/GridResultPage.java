/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.twostep;

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.query.h2.twostep.messages.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.extensions.communication.*;
import org.h2.value.*;

import javax.cache.*;
import java.util.*;

import static org.apache.ignite.internal.processors.query.h2.twostep.msg.GridH2ValueMessageFactory.*;

/**
 * Page result.
 */
public class GridResultPage {
    /** */
    private final UUID src;

    /** */
    protected final GridQueryNextPageResponse res;

    /** */
    private final int rowsInPage;

    /** */
    private final boolean last;

    /** */
    private Iterator<Value[]> rows;

    /**
     * @param ctx Kernal context.
     * @param src Source.
     * @param res Response.
     * @param last If this is the globally last page.
     */
    @SuppressWarnings("unchecked")
    public GridResultPage(final GridKernalContext ctx, UUID src, GridQueryNextPageResponse res, boolean last) {
        assert src != null;

        this.src = src;
        this.res = res;
        this.last = last;

        if (last)
            assert res == null : "The last page must be dummy.";

        // res == null means that it is a terminating dummy page for the given source node ID.
        if (res != null) {
            Collection<?> plainRows = res.plainRows();

            if (plainRows != null) {
                rowsInPage = plainRows.size();

                rows = (Iterator<Value[]>)plainRows.iterator();
            }
            else {
                final int cols = res.columns();

                rowsInPage = res.values().size() / cols;

                final Iterator<Message> valsIter = res.values().iterator();

                rows = new Iterator<Value[]>() {
                    /** */
                    int rowIdx;

                    @Override public boolean hasNext() {
                        return rowIdx < rowsInPage;
                    }

                    @Override public Value[] next() {
                        if (!hasNext())
                            throw new NoSuchElementException();

                        rowIdx++;

                        try {
                            return fillArray(valsIter, new Value[cols], ctx);
                        }
                        catch (IgniteCheckedException e) {
                            throw new CacheException(e);
                        }
                    }

                    @Override public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        }
        else {
            rowsInPage = 0;

            rows = Collections.emptyIterator();
        }
    }

    /**
     * @return {@code true} If this is a dummy last page for all the sources.
     */
    public boolean isLast() {
        return last;
    }

    /**
     * @return Number on rows in this page.
     */
    public int rowsInPage() {
        return rowsInPage;
    }

    /**
     * @return Rows.
     */
    public Iterator<Value[]> rows() {
        Iterator<Value[]> r = rows;

        assert r != null;

        rows = null;

        return r;
    }

    /**
     * @return Result source node ID.
     */
    public UUID source() {
        return src;
    }

    /**
     * @return Response.
     */
    public GridQueryNextPageResponse response() {
        return res;
    }

    /**
     * Request next page.
     */
    public void fetchNextPage() {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridResultPage.class, this);
    }
}
