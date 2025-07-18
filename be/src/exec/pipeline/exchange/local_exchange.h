// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <memory>
#include <utility>

#include "column/vectorized_fwd.h"
#include "exec/chunk_buffer_memory_manager.h"
#include "exec/pipeline/exchange/local_exchange_source_operator.h"
#include "exec/pipeline/exchange/shuffler.h"
#include "exprs/expr_context.h"
#include "util/runtime_profile.h"

namespace starrocks {
class ExprContext;
class RuntimeState;

namespace pipeline {

class Partitioner {
public:
    Partitioner(LocalExchangeSourceOperatorFactory* source) : _source(source) {}

    virtual ~Partitioner() = default;

    virtual Status shuffle_channel_ids(const ChunkPtr& chunk, int32_t num_partitions) = 0;

    // Divide chunk into shuffle partitions.
    // partition_row_indexes records the row indexes for the current shuffle index.
    // Sender will arrange the row indexes according to partitions.
    // For example, if there are 3 channels, it will put partition 0's row first,
    // then partition 1's row indexes, then put partition 2's row indexes in the last.
    Status partition_chunk(const ChunkPtr& chunk, int32_t num_partitions, std::vector<uint32_t>& partition_row_indexes);

    // Send chunk to each source by using `partition_row_indexes`.
    Status send_chunk(const ChunkPtr& chunk, const std::shared_ptr<std::vector<uint32_t>>& partition_row_indexes);

    size_t partition_begin_offset(size_t partition_id) { return _partition_row_indexes_start_points[partition_id]; }

    size_t partition_end_offset(size_t partition_id) { return _partition_row_indexes_start_points[partition_id + 1]; }

    size_t partition_memory_usage(size_t partition_id) {
        if (partition_id >= _partition_memory_usage.size() || partition_id < 0) {
            throw std::runtime_error(fmt::format("invalid index {} to get partition memory usage, whose size = {}.",
                                                 partition_id, _partition_memory_usage.size()));
        } else {
            return _partition_memory_usage[partition_id];
        }
    }

protected:
    LocalExchangeSourceOperatorFactory* _source;

    // This array record the channel start point in _row_indexes
    // And the last item is the number of rows of the current shuffle chunk.
    // It will easy to get number of rows belong to one channel by doing
    // _partition_row_indexes_start_points[i + 1] - _partition_row_indexes_start_points[i]
    std::vector<size_t> _partition_row_indexes_start_points;
    std::vector<size_t> _partition_memory_usage;
    std::vector<uint32_t> _shuffle_channel_id;
};

// Shuffle by partition columns and partition type.
class ShufflePartitioner final : public Partitioner {
public:
    ShufflePartitioner(LocalExchangeSourceOperatorFactory* source, const TPartitionType::type part_type,
                       const std::vector<ExprContext*>& partition_expr_ctxs)
            : Partitioner(source), _part_type(part_type), _partition_expr_ctxs(partition_expr_ctxs) {
        _partitions_columns.resize(partition_expr_ctxs.size());
        _hash_values.reserve(source->runtime_state()->chunk_size());
    }
    ~ShufflePartitioner() override = default;

    Status shuffle_channel_ids(const ChunkPtr& chunk, int32_t num_partitions) override;

private:
    const TPartitionType::type _part_type;
    // Compute per-row partition values.
    const std::vector<ExprContext*>& _partition_expr_ctxs;
    Columns _partitions_columns;
    std::vector<uint32_t> _hash_values;
    std::unique_ptr<Shuffler> _shuffler;
};

// Random shuffle row-by-row for each chunk of source.
class RandomPartitioner final : public Partitioner {
public:
    RandomPartitioner(LocalExchangeSourceOperatorFactory* source) : Partitioner(source) {}

    ~RandomPartitioner() override = default;

    Status shuffle_channel_ids(const ChunkPtr& chunk, int32_t num_partitions) override;
};

// Inspire from com.facebook.presto.operator.exchange.LocalExchanger
// Exchange the local data from local sink operator to local source operator
class LocalExchanger {
public:
    explicit LocalExchanger(std::string name, std::shared_ptr<ChunkBufferMemoryManager> memory_manager,
                            LocalExchangeSourceOperatorFactory* source)
            : _name(std::move(name)), _memory_manager(std::move(memory_manager)), _source(source) {
        source->set_exchanger(this);
    }

    virtual ~LocalExchanger() = default;

    enum class PassThroughType { CHUNK = 0, RANDOM = 1, ADPATIVE = 2, SCALE = 3, DIRECT = 4 };

    virtual Status prepare(RuntimeState* state) { return Status::OK(); }
    virtual void close(RuntimeState* state) {}

    virtual Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) = 0;

    virtual void finish(RuntimeState* state) {
        if (decr_sinker() == 1) {
            for (auto* source : _source->get_sources()) {
                static_cast<void>(source->set_finishing(state));
            }
        }
    }

    // All LocalExchangeSourceOperators have finished.
    bool is_all_sources_finished() const { return _finished_source_number == _source->get_sources().size(); }

    void finish_source() { _finished_source_number++; }

    void epoch_finish(RuntimeState* state) {
        if (incr_epoch_finished_sinker() == _sink_number) {
            for (auto* source : _source->get_sources()) {
                static_cast<void>(source->set_epoch_finishing(state));
            }
            // reset the number to be reused in the next epoch.
            _epoch_finished_sinker = 0;
        }
    }

    const std::string& name() const { return _name; }

    bool need_input() const;

    virtual void incr_sinker() { _sink_number++; }
    int32_t decr_sinker() { return _sink_number--; }

    int32_t source_dop() const { return _source->get_sources().size(); }

    int32_t incr_epoch_finished_sinker() { return ++_epoch_finished_sinker; }

    size_t get_memory_usage() const { return _memory_manager->get_memory_usage(); }

    void attach_sink_observer(RuntimeState* state, pipeline::PipelineObserver* observer) {
        _sink_observable.add_observer(state, observer);
    }

    auto defer_notify_sink() {
        return DeferOp([this]() {
            if (_memory_manager->full_events_changed() || is_all_sources_finished()) {
                _sink_observable.notify_sink_observers();
            }
        });
    }

protected:
    const std::string _name;
    std::shared_ptr<ChunkBufferMemoryManager> _memory_manager;
    std::atomic<int32_t> _sink_number = 0;
    std::atomic<int32_t> _finished_source_number = 0;
    LocalExchangeSourceOperatorFactory* _source;

    // Stream MV
    std::atomic<int32_t> _epoch_finished_sinker = 0;

private:
    Observable _sink_observable;
};

// Exchange the local data for shuffle
class PartitionExchanger final : public LocalExchanger {
public:
    PartitionExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                       LocalExchangeSourceOperatorFactory* source, const TPartitionType::type part_type,
                       std::vector<ExprContext*> _partition_expr_ctxs);

    ~PartitionExchanger() override = default;

    Status prepare(RuntimeState* state) override;
    void close(RuntimeState* state) override;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

    void incr_sinker() override;

private:
    // Used for local shuffle exchanger.
    // The sink_driver_sequence-th local sink operator exclusively uses the sink_driver_sequence-th partitioner.
    // TODO(lzh): limit the size of _partitioners, because it will cost too much memory when dop is high.
    TPartitionType::type _part_type;
    std::vector<ExprContext*> _partition_exprs;
    std::vector<std::unique_ptr<ShufflePartitioner>> _partitioners;
};

// The input stream is already ordered by partition columns.
// This partitioner is going to split these partitions into different channels, and try to balance the amount of
// data across channels.
// And for better performance, there's no row-level split during the entire process.
class OrderedPartitionExchanger final : public LocalExchanger {
public:
    OrderedPartitionExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                              LocalExchangeSourceOperatorFactory* source,
                              std::vector<ExprContext*> partition_expr_ctxs);
    ~OrderedPartitionExchanger() override = default;

    Status prepare(RuntimeState* state) override;
    void close(RuntimeState* state) override;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    size_t _find_min_channel_id();

    std::vector<ExprContext*> _partition_exprs;
    std::vector<size_t> _channel_row_nums;
    Columns _previous_partition_columns;
    size_t _previous_channel_id;
    ChunkPtr _previous_chunk;
};

// key partition mainly means that the column value of each partition is the same.
// For external table sinks, the chunk received by operators after exchange need to ensure that
// the values of the partition columns are the same.
class KeyPartitionExchanger final : public LocalExchanger {
public:
    KeyPartitionExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                          LocalExchangeSourceOperatorFactory* source, std::vector<ExprContext*> _partition_expr_ctxs,
                          size_t num_sinks, std::vector<std::string> transform_exprs);

    Status prepare(RuntimeState* state) override;
    void close(RuntimeState* state) override;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    LocalExchangeSourceOperatorFactory* _source;
    const std::vector<ExprContext*> _partition_expr_ctxs;
    std::vector<std::string> _transform_exprs;
};

// Exchange the local data for broadcast
class BroadcastExchanger final : public LocalExchanger {
public:
    BroadcastExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                       LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("Broadcast", memory_manager, source) {}

    ~BroadcastExchanger() override = default;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;
};

// Exchange the local data for one local source operation
class PassthroughExchanger final : public LocalExchanger {
public:
    PassthroughExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                         LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("Passthrough", memory_manager, source) {}

    ~PassthroughExchanger() override = default;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    std::atomic<size_t> _next_accept_source = 0;
};

// Exchange the local data accroding to sink_driver_sequence
class DirectThroughExchanger final : public LocalExchanger {
public:
    DirectThroughExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                           LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("Passthrough", memory_manager, source) {}

    ~DirectThroughExchanger() override = default;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;
};

// Scale local source for connector sink
class ConnectorSinkPassthroughExchanger final : public LocalExchanger {
public:
    ConnectorSinkPassthroughExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                                      LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("ConnectorSinkPassthrough", memory_manager, source) {}

    ~ConnectorSinkPassthroughExchanger() override = default;

    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    std::atomic<size_t> _next_accept_source = 0;
    std::atomic<size_t> _writer_count = 1;
    std::atomic<size_t> _data_processed = 0;
};

// Random shuffle for each chunk of source.
class RandomPassthroughExchanger final : public LocalExchanger {
public:
    RandomPassthroughExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                               LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("RandomPassthrough", memory_manager, source) {}

    ~RandomPassthroughExchanger() override = default;

    void incr_sinker() override;
    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    std::vector<std::unique_ptr<RandomPartitioner>> _random_partitioners;
};

// When total chunk num is greater than num_partitions, passthrough chunk directyly, otherwise
// random shuffle each rows for the input chunk to seperate it evenly.
class AdaptivePassthroughExchanger final : public LocalExchanger {
public:
    AdaptivePassthroughExchanger(const std::shared_ptr<ChunkBufferMemoryManager>& memory_manager,
                                 LocalExchangeSourceOperatorFactory* source)
            : LocalExchanger("AdaptivePassthrough", memory_manager, source) {}

    ~AdaptivePassthroughExchanger() override = default;

    void incr_sinker() override;
    Status accept(const ChunkPtr& chunk, int32_t sink_driver_sequence) override;

private:
    std::vector<std::unique_ptr<RandomPartitioner>> _random_partitioners;

    std::atomic<size_t> _next_accept_source = 0;
    std::atomic<size_t> _source_total_chunk_num = 0;
    std::atomic<bool> _is_pass_through_by_chunk = false;
};

} // namespace pipeline
} // namespace starrocks
