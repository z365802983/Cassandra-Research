/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable.format.big;

import java.io.IOException;
import java.util.Iterator;

import com.google.common.collect.AbstractIterator;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.composites.Composite;
import org.apache.cassandra.io.sstable.CorruptSSTableException;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.ByteBufferUtil;

//遍历一行记录，直到碰到的字段名>=finishColumn为止
class SimpleSliceReader extends AbstractIterator<OnDiskAtom> implements OnDiskAtomIterator
{
    private final FileDataInput file;
    private final boolean needsClosing;
    private final Composite finishColumn;
    private final CellNameType comparator;
    private final ColumnFamily emptyColumnFamily;
    private final Iterator<OnDiskAtom> atomIterator;

    SimpleSliceReader(SSTableReader sstable, RowIndexEntry indexEntry, FileDataInput input, Composite finishColumn)
    {
        Tracing.trace("Seeking to partition beginning in data file");
        this.finishColumn = finishColumn;
        this.comparator = sstable.metadata.comparator;
        try
        {
            if (input == null)
            {
                this.file = sstable.getFileDataInput(indexEntry.position); //indexEntry.position是此行在Data.db文件的开始位置
                this.needsClosing = true;
            }
            else
            {
                this.file = input;
                input.seek(indexEntry.position);
                this.needsClosing = false;
            }

            // Skip key and data size
            ByteBufferUtil.skipShortLength(file);

            emptyColumnFamily = ArrayBackedSortedColumns.factory.create(sstable.metadata);
            emptyColumnFamily.delete(DeletionTime.serializer.deserialize(file));
            atomIterator = emptyColumnFamily.metadata().getOnDiskIterator(file, sstable.descriptor.version);
        }
        catch (IOException e)
        {
            sstable.markSuspect();
            throw new CorruptSSTableException(e, sstable.getFilename());
        }
    }

    protected OnDiskAtom computeNext()
    {
        if (!atomIterator.hasNext())
            return endOfData();

        OnDiskAtom column = atomIterator.next();
        if (!finishColumn.isEmpty() && comparator.compare(column.name(), finishColumn) > 0)
            return endOfData();

        return column;
    }

    public ColumnFamily getColumnFamily()
    {
        return emptyColumnFamily;
    }

    public void close() throws IOException
    {
        if (needsClosing)
            file.close();
    }

    public DecoratedKey getKey()
    {
        throw new UnsupportedOperationException();
    }
}
