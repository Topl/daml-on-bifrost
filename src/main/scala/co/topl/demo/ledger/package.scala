// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package co.topl.demo

import com.daml.ledger.resources.ResourceOwner
import com.daml.platform.akkastreams.dispatcher.Dispatcher

package object ledger {
  type Index = Int

  private[ledger] val StartIndex: Index = 0

  def dispatcherOwner: ResourceOwner[Dispatcher[Index]] =
    Dispatcher.owner(
      name = "in-memory-key-value-participant-state",
      zeroIndex = StartIndex,
      headAtInitialization = StartIndex
    )

  private[ledger] val RunnerName = "In-Memory Ledger"
}
