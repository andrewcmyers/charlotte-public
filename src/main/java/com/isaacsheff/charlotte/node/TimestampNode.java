package com.isaacsheff.charlotte.node;


import static com.isaacsheff.charlotte.node.HashUtil.sha3Hash;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.concurrent.ConcurrentHashMap.newKeySet;

import java.nio.file.Path;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.isaacsheff.charlotte.collections.BlockingConcurrentHashMap;
import com.isaacsheff.charlotte.collections.BlockingMap;
import com.isaacsheff.charlotte.fern.TimestampFern;
import com.isaacsheff.charlotte.proto.Block;
import com.isaacsheff.charlotte.proto.CharlotteNodeGrpc;
import com.isaacsheff.charlotte.proto.CryptoId;
import com.isaacsheff.charlotte.proto.Hash;
import com.isaacsheff.charlotte.proto.IntegrityAttestation;
import com.isaacsheff.charlotte.proto.IntegrityAttestation.SignedTimestampedReferences;
import com.isaacsheff.charlotte.proto.IntegrityAttestation.TimestampedReferences;
import com.isaacsheff.charlotte.proto.IntegrityPolicy;
import com.isaacsheff.charlotte.proto.Reference;
import com.isaacsheff.charlotte.proto.RequestIntegrityAttestationInput;
import com.isaacsheff.charlotte.proto.RequestIntegrityAttestationResponse;
import com.isaacsheff.charlotte.proto.SendBlocksInput;
import com.isaacsheff.charlotte.proto.SendBlocksResponse;
import com.isaacsheff.charlotte.proto.Signature;
import com.isaacsheff.charlotte.yaml.Config;
import com.isaacsheff.charlotte.yaml.Contact;

import io.grpc.stub.StreamObserver;

/**
 * A gRPC service for the Charlotte API.
 * gRPC separates "service" from "server."
 * One Server can run multiple Serivices.
 * This is a Service implementing the charlotte gRPC API.
 * It can be extended for more interesting implementations.
 * @author Isaac Sheff
 */
public class TimestampNode extends CharlotteNodeService {
  /**
   * Use logger for logging events on a CharlotteNodeService.
   */
  private static final Logger logger = Logger.getLogger(TimestampNode.class.getName());

  private final TimestampFern fern;
  private final Set<Reference.Builder> untimestamped;
  private int referencesPerAttestation;

  /**
   * Create a new service with the given map of blocks, and the given map of addresses.
   * No input is checked for correctness.
   * @param blockMap a map of known hashes and blocks
   * @param config the Configuration settings for this Service
   */
  public TimestampNode(int referencesPerAttestation, 
                       TimestampFern fern,
                       BlockingMap<Hash, Block> blockMap,
                       Config config) {
    super(blockMap, config);
    this.fern = fern;
    untimestamped = newKeySet();
    this.referencesPerAttestation = referencesPerAttestation;
  }

  /**
   * Create a new service with an empty map of blocks and an empty map of addresses.
   * @param config the Configuration settings for this Service
   */
  public TimestampNode(int referencesPerAttestation, TimestampFern fern, Config config) {
    super(config);
    this.fern = fern;
    untimestamped = newKeySet();
    this.referencesPerAttestation = referencesPerAttestation;
  }

  /**
   * Create a new service with an empty map of blocks and an empty map of addresses, parse configuration.
   * @param path the file path for the configuration file
   */
  public TimestampNode(int referencesPerAttestation, TimestampFern fern, Path path) {
    super(path);
    this.fern = fern;
    untimestamped = newKeySet();
    this.referencesPerAttestation = referencesPerAttestation;
  }

  /**
   * Create a new service with an empty map of blocks and an empty map of addresses, parse configuration.
   * @param filename the file name for the configuration file
   */
  public TimestampNode(int referencesPerAttestation, TimestampFern fern, String filename) {
    super(filename);
    this.fern = fern;
    untimestamped = newKeySet();
    this.referencesPerAttestation = referencesPerAttestation;
  }

  /**
   * Called after a new block has been received, and set to be broadcast to all other nodes.
   * Override this to make this Node do useful things.
   * @param block the newly received block
   * @return any SendBlockResponses (including error messages) to be sent back over the wire to the block's sender.
   */
  @Override
  public Iterable<SendBlocksResponse> afterBroadcastNewBlock(Block block) {
    boolean shouldRequestAttestation = false;
    final TimestampedReferences.Builder references = TimestampedReferences.newBuilder();
    untimestamped.add(Reference.newBuilder().setHash(sha3Hash(block)));
    synchronized(untimestamped) {
      if (untimestamped.size() >= referencesPerAttestation) {
        shouldRequestAttestation = true;
        for (Reference.Builder reference : untimestamped) {
          references.addBlock(reference);
        }
        untimestamped.clear();
      }
    }
    if (shouldRequestAttestation) {
      final RequestIntegrityAttestationResponse response = fern.requestIntegrityAttestation(
          RequestIntegrityAttestationInput.newBuilder().setPolicy(
            IntegrityPolicy.newBuilder().setFillInTheBlank(
              IntegrityAttestation.newBuilder().setSignedTimestampedReferences(
                SignedTimestampedReferences.newBuilder().
                  setSignature(
                    Signature.newBuilder().setCryptoId(getConfig().getCryptoId())). // signed by me
                  setTimestampedReferences(references)))).build());
      if (!response.getErrorMessage().equals("")) {
        return singleton(SendBlocksResponse.newBuilder().setErrorMessage(
                 "Problem while getting attestation for latest batch of blocks:\n"+
                 response.getErrorMessage()).build());
      }
    }
    return emptySet();
  }
}
