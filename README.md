# Daml-on-Bifrost

## An implementation of the DAML-on-X example for Topl Bifrost nodes

This implementation integrates a [DAML-on-X](https://github.com/digital-asset/daml-on-x-example) Driver with a Topl [Bifrost Node](https://github.com/Topl/Bifrost) and Topl ecosystem. The purpose of this driver is to enable off-chain smart contracts to be created and updated in tandem with the Topl blockchain, allowing smart contract calls to initiate and control Asset transactions on the blockchain.


### Warning: This is not a production-ready implementation, and should be used for testing purposes only.

---

## Usage

Run the server with the Ledger API service and connect to the default port 6865 with:

```sbt "run --port 6865 --role ledger```

Currently, this runs in a sandboxed environment and interactions between the driver and a running Bifrost node are being actively developed and tested.