/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchParseException;
import org.opensearch.Version;
import org.opensearch.cluster.AbstractNamedDiffable;
import org.opensearch.cluster.NamedDiff;
import org.opensearch.cluster.routing.WeightedRouting;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Contains metadata for weighted routing
 *
 * @opensearch.internal
 */
public class WeightedRoutingMetadata extends AbstractNamedDiffable<Metadata.Custom> implements Metadata.Custom {
    private static final Logger logger = LogManager.getLogger(WeightedRoutingMetadata.class);
    public static final String TYPE = "weighted_shard_routing";
    public static final String AWARENESS = "awareness";
    private WeightedRouting weightedRouting;

    public WeightedRouting getWeightedRouting() {
        return weightedRouting;
    }

    public WeightedRoutingMetadata setWeightedRouting(WeightedRouting weightedRouting) {
        this.weightedRouting = weightedRouting;
        return this;
    }

    public WeightedRoutingMetadata(StreamInput in) throws IOException {
        if (in.available() != 0) {
            this.weightedRouting = new WeightedRouting(in);
        }
    }

    public WeightedRoutingMetadata(WeightedRouting weightedRouting) {
        this.weightedRouting = weightedRouting;
    }

    @Override
    public EnumSet<Metadata.XContentContext> context() {
        return Metadata.API_AND_GATEWAY;
    }

    @Override
    public String getWriteableName() {
        return TYPE;
    }

    @Override
    public Version getMinimalSupportedVersion() {
        return Version.V_2_4_0;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (weightedRouting != null) {
            weightedRouting.writeTo(out);
        }
    }

    public static NamedDiff<Metadata.Custom> readDiffFrom(StreamInput in) throws IOException {
        return readDiffFrom(Metadata.Custom.class, TYPE, in);
    }

    public static WeightedRoutingMetadata fromXContent(XContentParser parser) throws IOException {
        String attrKey = null;
        Double attrValue;
        String attributeName = null;
        Map<String, Double> weights = new HashMap<>();
        WeightedRouting weightedRouting = null;
        XContentParser.Token token;
        String awarenessField = null;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                awarenessField = parser.currentName();
                if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                    throw new OpenSearchParseException(
                        "failed to parse weighted routing metadata  [{}], expected " + "object",
                        awarenessField
                    );
                }
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    attributeName = parser.currentName();
                    if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                        throw new OpenSearchParseException(
                            "failed to parse weighted routing metadata  [{}], expected" + " object",
                            attributeName
                        );
                    }
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            attrKey = parser.currentName();
                        } else if (token == XContentParser.Token.VALUE_NUMBER) {
                            attrValue = Double.parseDouble(parser.text());
                            weights.put(attrKey, attrValue);
                        } else {
                            throw new OpenSearchParseException(
                                "failed to parse weighted routing metadata attribute " + "[{}], unknown type",
                                attributeName
                            );
                        }
                    }
                }
            }
        }
        weightedRouting = new WeightedRouting(attributeName, weights);
        return new WeightedRoutingMetadata(weightedRouting);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WeightedRoutingMetadata that = (WeightedRoutingMetadata) o;
        return weightedRouting.equals(that.weightedRouting);
    }

    @Override
    public int hashCode() {
        return weightedRouting.hashCode();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        toXContent(weightedRouting, builder);
        return builder;
    }

    public static void toXContent(WeightedRouting weightedRouting, XContentBuilder builder) throws IOException {
        builder.startObject(AWARENESS);
        builder.startObject(weightedRouting.attributeName());
        for (Map.Entry<String, Double> entry : weightedRouting.weights().entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }
        builder.endObject();
        builder.endObject();
    }

    @Override
    public String toString() {
        return Strings.toString(this);
    }
}
