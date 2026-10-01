#include "myndhamr/alignment.hpp"
#include <Eigen/Geometry>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <limits>
namespace {
void check(bool value,const char* detail){if(!value)throw std::runtime_error(detail);}
void rejects(const std::vector<myndhamr::Correspondence>& pairs){
    try{myndhamr::align_similarity(pairs);throw std::runtime_error("Invalid alignment accepted");}
    catch(const std::invalid_argument&){}
}
std::vector<myndhamr::Correspondence> fixture(double scale,const Eigen::Matrix3d& rotation,const Eigen::Vector3d& translation){
    std::vector<myndhamr::Correspondence> result;
    for(int i=0;i<20;++i){const Eigen::Vector3d source(std::sin(i*0.7),std::cos(i*0.4),std::sin(i*0.31));result.push_back({source,scale*rotation*source+translation});}
    return result;
}
}
int main(){try{
    const Eigen::Matrix3d identity=Eigen::Matrix3d::Identity();
    const Eigen::Matrix3d rotation=Eigen::AngleAxisd(1.1,Eigen::Vector3d(1,2,-3).normalized()).toRotationMatrix();
    for(const double scale:{0.001,0.25,1.0,7.3,1000.0})for(const auto& r:{identity,rotation}){
        const Eigen::Vector3d translation(1.2,-3.4,8.7);
        const auto pairs=fixture(scale,r,translation);myndhamr::AlignmentOptions opt;opt.minimum_metric_baseline=0.0001;
        const auto result=myndhamr::align_similarity(pairs,opt);
        check(std::abs(result.scale/scale-1)<1e-10,"Wrong scale");
        check((result.rotation_world_sfm-r).norm()<1e-10,"Inverted/transposed/wrong-axis rotation");
        check((result.translation_world_sfm-translation).norm()<1e-10,"Wrong translation");
        for(const auto& p:pairs){check((result.apply(p.source)-p.target).norm()<1e-8,"Wrong transform direction");
            const Eigen::Vector3d roundtrip=r.transpose()*(p.target-translation)/scale;check((roundtrip-p.source).norm()<1e-10,"Roundtrip units/direction");}
    }
    auto noisy=fixture(2.3,rotation,{3,2,-1});
    for(std::size_t i=0;i<noisy.size();++i){if(i%5==0)noisy[i].target+=Eigen::Vector3d(2,-3,5);else noisy[i].target+=Eigen::Vector3d(std::sin(i)*0.002,std::cos(i)*0.002,0);}
    const auto robust=myndhamr::align_similarity(noisy);
    check(std::abs(robust.scale/2.3-1)<0.002,"Noisy robust scale");
    check((robust.rotation_world_sfm-rotation).norm()<0.003,"Noisy robust rotation");
    check((robust.translation_world_sfm-Eigen::Vector3d(3,2,-1)).norm()<0.003,"Noisy robust translation");
    for(std::size_t i=0;i<noisy.size();++i)check(robust.inliers[i]==(i%5!=0),"Outlier classification");
    const auto repeat=myndhamr::align_similarity(noisy);check(repeat.scale==robust.scale,"Non-deterministic robust sampling");
    rejects({});rejects({{{0,0,0},{0,0,0}},{{1,0,0},{1,0,0}}});
    std::vector<myndhamr::Correspondence> line;for(int i=0;i<10;++i)line.push_back({{double(i),0,0},{double(i),0,0}});rejects(line);
    auto flat=fixture(1,identity,{0,0,0});for(auto& p:flat)p.target={0,0,0};rejects(flat);
    std::vector<myndhamr::Correspondence> target_line;
    for(int i=0;i<10;++i)target_line.push_back({{double(i),i%2?.01:-.01,0},{double(i),0,0}});
    rejects(target_line);
    auto mirror=fixture(1,identity,{0,0,0});for(auto& p:mirror)p.target.x()*=-1;rejects(mirror);
    auto tiny=fixture(1e-4,identity,{0,0,0});rejects(tiny);
    auto bad=fixture(1,identity,{0,0,0});bad[0].source.x()=std::numeric_limits<double>::quiet_NaN();rejects(bad);
    std::cout<<"geometry alignment: exact scales/rotations/translations, inverse, robust noise/outliers, reflection/nonfinite/degeneracy passed\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
