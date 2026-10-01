#include "myndhamr/alignment.hpp"
#include <Eigen/SVD>
#include <Eigen/LU>
#include <algorithm>
#include <cmath>
#include <random>
#include <stdexcept>
#include <limits>

namespace myndhamr {
Eigen::Vector3d Similarity::apply(const Eigen::Vector3d& point) const {
    return scale * rotation_world_sfm * point + translation_world_sfm;
}
namespace {
Similarity fit(const std::vector<Correspondence>& pairs, const std::vector<std::size_t>& ids) {
    Eigen::Vector3d source_mean = Eigen::Vector3d::Zero(), target_mean = Eigen::Vector3d::Zero();
    for (auto i : ids) { source_mean += pairs[i].source; target_mean += pairs[i].target; }
    source_mean /= static_cast<double>(ids.size()); target_mean /= static_cast<double>(ids.size());
    Eigen::Matrix3d covariance = Eigen::Matrix3d::Zero(), source_scatter = Eigen::Matrix3d::Zero(), target_scatter = Eigen::Matrix3d::Zero();
    double variance = 0;
    for (auto i : ids) {
        const Eigen::Vector3d x = pairs[i].source - source_mean;
        const Eigen::Vector3d y = pairs[i].target - target_mean;
        covariance += y * x.transpose(); source_scatter += x * x.transpose(); target_scatter += y * y.transpose(); variance += x.squaredNorm();
    }
    Eigen::JacobiSVD<Eigen::Matrix3d> scatter_svd(source_scatter);
    const auto eigenvalues = scatter_svd.singularValues();
    if (!(variance > 1e-20) || eigenvalues[1] <= eigenvalues[0] * 1e-8)
        throw std::invalid_argument("Degenerate alignment: camera centers are coincident or collinear");
    Eigen::JacobiSVD<Eigen::Matrix3d> target_svd(target_scatter);
    const auto target_values = target_svd.singularValues();
    if (target_values[0] <= 1e-20 || target_values[1] <= target_values[0] * 1e-8)
        throw std::invalid_argument("Degenerate target capture trajectory: camera centers are coincident or collinear");
    Eigen::JacobiSVD<Eigen::Matrix3d> svd(covariance, Eigen::ComputeFullU | Eigen::ComputeFullV);
    Eigen::Vector3d signs(1, 1, (svd.matrixU() * svd.matrixV().transpose()).determinant() < 0 ? -1 : 1);
    const Eigen::Matrix3d rotation = svd.matrixU() * signs.asDiagonal() * svd.matrixV().transpose();
    const double scale = svd.singularValues().dot(signs) / variance;
    if (!std::isfinite(scale) || scale <= 0 || !rotation.allFinite())
        throw std::invalid_argument("Alignment requires a finite positive scale and proper rotation");
    return {scale, rotation, target_mean - scale * rotation * source_mean, {}, {}, eigenvalues[1]/eigenvalues[0]};
}
void evaluate(Similarity& model, const std::vector<Correspondence>& pairs, double threshold) {
    model.residual_meters.clear(); model.inliers.clear();
    for (const auto& p : pairs) {
        const double residual = (model.apply(p.source) - p.target).norm();
        model.residual_meters.push_back(residual); model.inliers.push_back(residual <= threshold);
    }
}
std::vector<std::size_t> inlier_ids(const Similarity& model) {
    std::vector<std::size_t> result;
    for (std::size_t i=0; i<model.inliers.size(); ++i) if(model.inliers[i]) result.push_back(i);
    return result;
}
}
Similarity align_similarity(const std::vector<Correspondence>& pairs, const AlignmentOptions& options) {
    if(pairs.size() < 3) throw std::invalid_argument("Alignment needs at least three corresponding camera centers");
    if(!std::isfinite(options.threshold_meters) || options.threshold_meters <= 0 ||
       !std::isfinite(options.minimum_inlier_fraction) || options.minimum_inlier_fraction <= 0 || options.minimum_inlier_fraction > 1 ||
       !std::isfinite(options.minimum_metric_baseline) || options.minimum_metric_baseline <= 0 || options.maximum_trials == 0)
        throw std::invalid_argument("Invalid alignment resource/tolerance configuration");
    for(const auto& p : pairs) if(!p.source.allFinite() || !p.target.allFinite())
        throw std::invalid_argument("Nonfinite camera-center correspondence");
    // Reproducible random samples; full-set model also tested to avoid needless noise sensitivity.
    std::mt19937 generator(0);
    std::uniform_int_distribution<std::size_t> draw(0, pairs.size()-1);
    Similarity best{}; std::size_t best_count=0; double best_cost=std::numeric_limits<double>::infinity();
    const auto consider = [&](const std::vector<std::size_t>& ids) {
        try {
            auto candidate=fit(pairs,ids); evaluate(candidate,pairs,options.threshold_meters);
            const auto count=inlier_ids(candidate).size();
            double cost=0; for(double residual:candidate.residual_meters) cost+=std::min(residual*residual,options.threshold_meters*options.threshold_meters);
            if(count>best_count || (count==best_count && cost<best_cost)) {best=std::move(candidate);best_count=count;best_cost=cost;}
        } catch(const std::invalid_argument&) { /* Degenerate hypotheses are not measurements. */ }
    };
    std::vector<std::size_t> all; for(std::size_t i=0;i<pairs.size();++i) all.push_back(i);
    consider(all);
    for(unsigned trial=0;trial<options.maximum_trials;++trial) {
        const auto a=draw(generator),b=draw(generator),c=draw(generator);
        if(a==b || a==c || b==c) continue;
        consider({a,b,c});
        if(best_count==pairs.size()) break;
    }
    const auto required=std::max<std::size_t>(3,static_cast<std::size_t>(std::ceil(options.minimum_inlier_fraction*static_cast<double>(pairs.size()))));
    if(best_count<required) throw std::invalid_argument("No trustworthy Sim(3) consensus: fewer than required inlier camera centers");
    // Refit, then reassess; final membership describes the final transform, not a sampled model.
    for(unsigned pass=0;pass<5;++pass) {
        const auto ids=inlier_ids(best); auto refined=fit(pairs,ids); evaluate(refined,pairs,options.threshold_meters);
        const auto next_ids=inlier_ids(refined); best=std::move(refined);
        if(next_ids.size()<required) throw std::invalid_argument("Alignment consensus collapsed during refit");
        if(next_ids==ids) break;
    }
    Eigen::Vector3d mean=Eigen::Vector3d::Zero(); const auto ids=inlier_ids(best);
    for(auto i:ids) mean+=pairs[i].target;
    mean/=static_cast<double>(ids.size()); double radius_squared=0;
    for(auto i:ids) radius_squared+=(pairs[i].target-mean).squaredNorm();
    if(std::sqrt(radius_squared/static_cast<double>(ids.size()))<options.minimum_metric_baseline)
        throw std::invalid_argument("Insufficient metric camera baseline for reliable scale");
    return best;
}
}
